package com.verifolio.admin

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import com.verifolio.admin.application.AdminTotpService
import org.apache.commons.codec.binary.Base32
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class AdminTotpServiceTest {

    private val service = AdminTotpService()

    @Test
    fun `current code verifies true`() {
        val secret = service.generateSecret()
        assertThat(service.verify(secret, service.currentCode(secret))).isTrue()
    }

    @Test
    fun `wrong code verifies false`() {
        val secret = service.generateSecret()
        val current = service.currentCode(secret)
        val wrong = if (current == "000000") "123456" else "000000"
        assertThat(service.verify(secret, wrong)).isFalse()
    }

    @Test
    fun `code from 60s ago is rejected (skew is +-1 step)`() {
        val secret = service.generateSecret()
        val key = SecretKeySpec(Base32().decode(secret), "HmacSHA1")
        val generator = TimeBasedOneTimePasswordGenerator()
        // Two steps (60s) in the past — outside the accepted [-30s, +30s] window.
        val staleCode = "%06d".format(
            generator.generateOneTimePassword(key, Instant.now().minusSeconds(60)),
        )
        assertThat(service.verify(secret, staleCode)).isFalse()
    }

    @Test
    fun `otpauth uri has expected shape`() {
        val uri = service.otpauthUri("admin@example.com", "JBSWY3DPEHPK3PXP", "local")
        assertThat(uri).isEqualTo(
            "otpauth://totp/Verifolio%20(local):admin@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Verifolio",
        )
    }
}
