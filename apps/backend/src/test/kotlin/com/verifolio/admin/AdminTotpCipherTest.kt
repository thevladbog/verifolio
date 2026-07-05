package com.verifolio.admin

import com.verifolio.admin.application.AdminTotpCipher
import com.verifolio.platform.VerifolioProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class AdminTotpCipherTest {

    // The dev-only secret string the local cell ships (AES-256 key = SHA-256 of it).
    private val cipher = AdminTotpCipher(VerifolioProperties.Admin.LOCAL_DEV_TOTP_SECRET_KEY)

    @Test
    fun `round-trips plaintext`() {
        val plain = "JBSWY3DPEHPK3PXP"
        assertThat(cipher.decrypt(cipher.encrypt(plain))).isEqualTo(plain)
    }

    @Test
    fun `two encryptions of the same plaintext differ (fresh IV)`() {
        val plain = "JBSWY3DPEHPK3PXP"
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        assertThat(a).isNotEqualTo(b)
        // Both still decrypt back to the same plaintext.
        assertThat(cipher.decrypt(a)).isEqualTo(plain)
        assertThat(cipher.decrypt(b)).isEqualTo(plain)
    }

    @Test
    fun `tampered ciphertext fails to decrypt (GCM auth tag)`() {
        val stored = cipher.encrypt("JBSWY3DPEHPK3PXP")
        val (ivB64, ctB64) = stored.split(":")
        val ct = Base64.getDecoder().decode(ctB64)
        ct[0] = (ct[0].toInt() xor 0x01).toByte() // flip one bit
        val tampered = "$ivB64:${Base64.getEncoder().encodeToString(ct)}"
        assertThatThrownBy { cipher.decrypt(tampered) }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `blank secret is rejected`() {
        assertThatThrownBy { AdminTotpCipher("  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a different secret cannot decrypt`() {
        val stored = cipher.encrypt("JBSWY3DPEHPK3PXP")
        assertThatThrownBy { AdminTotpCipher("some-other-cell-secret").decrypt(stored) }
            .isInstanceOf(Exception::class.java)
    }
}
