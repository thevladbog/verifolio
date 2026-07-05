package com.verifolio.admin

import com.verifolio.admin.application.AdminTotpCipher
import com.verifolio.platform.VerifolioProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class AdminTotpCipherTest {

    // Same 32-byte AES-256 key the local cell ships (dev-only).
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
    fun `wrong-length key is rejected`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThatThrownBy { AdminTotpCipher(shortKey) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
