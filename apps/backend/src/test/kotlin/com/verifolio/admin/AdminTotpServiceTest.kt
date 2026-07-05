package com.verifolio.admin

import com.verifolio.admin.application.AdminTotpService
import com.verifolio.admin.application.Base32
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

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
        // Two steps (60s) in the past — outside the accepted [-30s, +30s] window.
        val staleCode = service.codeAt(secret, Instant.now().minusSeconds(60))
        assertThat(service.verify(secret, staleCode)).isFalse()
    }

    @Test
    fun `matches the RFC 6238 known-answer vector`() {
        // RFC 6238 Appendix B: SHA-1 seed ASCII "12345678901234567890" (20 bytes),
        // at T=59s the 6-digit TOTP is 287082.
        val secretBase32 = Base32.encode("12345678901234567890".toByteArray())
        assertThat(service.codeAt(secretBase32, Instant.ofEpochSecond(59))).isEqualTo("287082")
        // T=1111111109 → 081804 ; T=1234567890 → 005924 (both from the RFC SHA-1 column).
        assertThat(service.codeAt(secretBase32, Instant.ofEpochSecond(1111111109))).isEqualTo("081804")
        assertThat(service.codeAt(secretBase32, Instant.ofEpochSecond(1234567890))).isEqualTo("005924")
    }

    @Test
    fun `base32 round-trips`() {
        val bytes = "12345678901234567890".toByteArray()
        assertThat(Base32.decode(Base32.encode(bytes))).isEqualTo(bytes)
    }

    @Test
    fun `otpauth uri has expected shape`() {
        val uri = service.otpauthUri("admin@example.com", "JBSWY3DPEHPK3PXP", "local")
        assertThat(uri).isEqualTo(
            "otpauth://totp/Verifolio%20(local):admin@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Verifolio",
        )
    }
}
