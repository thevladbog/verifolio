package com.verifolio.admin.application

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import org.apache.commons.codec.binary.Base32
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP for admin MFA (spec §TOTP specifics): 30s step, 6 digits, HMAC-SHA1 —
 * the authenticator-app standard — via com.eatthepath:java-otp. Secrets are 20 random bytes,
 * Base32-encoded (no padding, upper-case) for the otpauth URI and the storage-plaintext that
 * AdminTotpCipher then encrypts. Verification allows ±1 step (30s) of clock skew.
 */
@Service
class AdminTotpService {

    private val random = SecureRandom()
    private val generator = TimeBasedOneTimePasswordGenerator() // defaults: 30s, 6 digits, HmacSHA1

    /** A fresh 20-byte secret, Base32 (upper-case, unpadded) — the otpauth/storage form. */
    fun generateSecret(): String {
        val bytes = ByteArray(20).also { random.nextBytes(it) }
        return Base32().encodeAsString(bytes).trimEnd('=')
    }

    /**
     * `otpauth://totp/Verifolio%20(<region>):<email>?secret=<base32>&issuer=Verifolio` (spec).
     * Only the space is percent-encoded, matching the normative example; region/email are
     * expected to be simple label components.
     */
    fun otpauthUri(email: String, secretBase32: String, region: String): String {
        val label = "Verifolio ($region):$email".replace(" ", "%20")
        return "otpauth://totp/$label?secret=$secretBase32&issuer=Verifolio"
    }

    /** True iff [code] matches the TOTP for [secretBase32] at now, now-30s, or now+30s (±1 step). */
    fun verify(secretBase32: String, code: String): Boolean {
        val candidate = code.trim()
        if (!candidate.matches(SIX_DIGITS)) return false
        val key = SecretKeySpec(Base32().decode(secretBase32), "HmacSHA1")
        val now = Instant.now()
        return SKEW_STEPS.any { offset ->
            codeAt(key, now.plusSeconds(offset)) == candidate
        }
    }

    /** Test helper: the current 6-digit code for [secretBase32]. */
    fun currentCode(secretBase32: String): String {
        val key = SecretKeySpec(Base32().decode(secretBase32), "HmacSHA1")
        return codeAt(key, Instant.now())
    }

    private fun codeAt(key: SecretKeySpec, at: Instant): String =
        "%06d".format(generator.generateOneTimePassword(key, at))

    private companion object {
        val SIX_DIGITS = Regex("\\d{6}")
        val SKEW_STEPS = listOf(0L, -30L, 30L)
    }
}
