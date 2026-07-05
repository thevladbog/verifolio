package com.verifolio.admin.application

import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP for admin MFA (spec §TOTP specifics): 30s step, 6 digits, HMAC-SHA1 —
 * the authenticator-app standard. Implemented on the JDK only (javax.crypto.Mac + an
 * inline RFC 4648 Base32) so the module carries no third-party crypto dependency —
 * important for a product deployable into restricted regional networks. Secrets are 20
 * random bytes, Base32-encoded (upper-case, unpadded) for the otpauth URI and the
 * storage-plaintext that AdminTotpCipher then encrypts. Verification allows ±1 step
 * (30s) of clock skew.
 */
@Service
class AdminTotpService {

    private val random = SecureRandom()

    /** A fresh 20-byte secret, Base32 (upper-case, unpadded) — the otpauth/storage form. */
    fun generateSecret(): String {
        val bytes = ByteArray(20).also { random.nextBytes(it) }
        return Base32.encode(bytes)
    }

    /**
     * `otpauth://totp/<label>?secret=<base32>&issuer=Verifolio` (spec). The label is the otpauth
     * `issuer:account` form — each component (`Verifolio (<region>)` and `<email>`) is percent-encoded
     * and joined by a literal `:`, and the `secret`/`issuer` query values are percent-encoded — so
     * reserved characters in a region or email (`@`, spaces, parens, `&`, `?`, …) can't break the URI or
     * an authenticator's enrollment parse.
     */
    fun otpauthUri(email: String, secretBase32: String, region: String): String {
        val label = "${encode("Verifolio ($region)")}:${encode(email)}"
        return "otpauth://totp/$label?secret=${encode(secretBase32)}&issuer=${encode(ISSUER)}"
    }

    /** URLEncoder for a URI component: UTF-8, but emit `%20` for space (URLEncoder's `+` is only valid in a form body). */
    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    /** True iff [code] matches the TOTP for [secretBase32] at now, now-30s, or now+30s (±1 step). */
    fun verify(secretBase32: String, code: String): Boolean {
        val candidate = code.trim()
        if (!candidate.matches(SIX_DIGITS)) return false
        val key = SecretKeySpec(Base32.decode(secretBase32), "HmacSHA1")
        val now = Instant.now()
        return SKEW_STEPS.any { offset -> codeAt(key, now.plusSeconds(offset)) == candidate }
    }

    /** Test helper: the current 6-digit code for [secretBase32]. */
    fun currentCode(secretBase32: String): String = codeAt(secretBase32, Instant.now())

    /** The RFC 6238 6-digit code for [secretBase32] at [at] (exposed for tests/skew checks). */
    fun codeAt(secretBase32: String, at: Instant): String =
        codeAt(SecretKeySpec(Base32.decode(secretBase32), "HmacSHA1"), at)

    /** RFC 6238: HOTP over the 30s time-step counter, dynamically truncated to 6 digits. */
    private fun codeAt(key: SecretKeySpec, at: Instant): String {
        val counter = at.epochSecond / STEP_SECONDS
        val msg = ByteArray(8)
        for (i in 7 downTo 0) msg[i] = (counter ushr ((7 - i) * 8)).toByte()
        val hmac = Mac.getInstance("HmacSHA1").apply { init(key) }.doFinal(msg)
        val offset = (hmac[hmac.size - 1].toInt() and 0x0f)
        val binary = ((hmac[offset].toInt() and 0x7f) shl 24) or
            ((hmac[offset + 1].toInt() and 0xff) shl 16) or
            ((hmac[offset + 2].toInt() and 0xff) shl 8) or
            (hmac[offset + 3].toInt() and 0xff)
        return "%06d".format(binary % 1_000_000)
    }

    private companion object {
        val SIX_DIGITS = Regex("\\d{6}")
        val SKEW_STEPS = listOf(0L, -30L, 30L)
        const val STEP_SECONDS = 30L
        const val ISSUER = "Verifolio"
    }
}

/** Minimal RFC 4648 Base32 (upper-case, unpadded encode; case-insensitive, padding-tolerant decode). */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(ALPHABET[(buffer ushr bitsLeft) and 0x1f])
            }
        }
        if (bitsLeft > 0) sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1f])
        return sb.toString()
    }

    fun decode(encoded: String): ByteArray {
        val clean = encoded.trim().trimEnd('=').uppercase()
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid Base32 character" }
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer ushr bitsLeft) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
