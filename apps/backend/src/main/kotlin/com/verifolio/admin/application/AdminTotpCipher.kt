package com.verifolio.admin.application

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for TOTP secrets at rest (spec §TOTP specifics). A plaintext MFA seed
 * in the app DB would defeat MFA, so secrets are always encrypted. The AES-256 key is derived as
 * SHA-256 of the per-cell `verifolio.admin.totp-secret-key` (an ops-provided secret string, not a
 * committed base64 blob — so no high-entropy literal ships in the repo). Each encrypt uses a fresh
 * random 12-byte IV; the stored form is `base64(iv):base64(ciphertext+tag)`. 128-bit GCM auth tag
 * makes tampering detectable (decrypt throws). No crypto dependency (javax.crypto only).
 */
class AdminTotpCipher(secret: String) {

    private val key: SecretKeySpec = run {
        require(secret.isNotBlank()) { "verifolio.admin.totp-secret-key must not be blank" }
        val derived = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        SecretKeySpec(derived, "AES")
    }

    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder()
        return "${enc.encodeToString(iv)}:${enc.encodeToString(ct)}"
    }

    fun decrypt(stored: String): String {
        val parts = stored.split(":")
        require(parts.size == 2) { "malformed TOTP ciphertext" }
        val dec = Base64.getDecoder()
        val iv = dec.decode(parts[0])
        val ct = dec.decode(parts[1])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
