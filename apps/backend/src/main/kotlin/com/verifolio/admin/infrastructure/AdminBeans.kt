package com.verifolio.admin.infrastructure

import com.verifolio.admin.application.AdminTotpCipher
import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.VerifolioProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
internal class AdminBeans {

    /** AES-256-GCM cipher for TOTP secrets at rest, keyed by the per-cell config (spec §TOTP). */
    @Bean
    fun adminTotpCipher(props: VerifolioProperties) = AdminTotpCipher(props.admin.totpSecretKey)

    /** Per-email admin magic-link window (5/15min), mirroring the identity magic-link limiter. */
    @Bean("adminMagicLinkEmailLimiter")
    fun adminMagicLinkEmailLimiter() = SlidingWindowRateLimiter(limit = 5, window = Duration.ofMinutes(15))

    /** Per-IP admin magic-link window; reuses the auth IP limit so integration tests (shared 127.0.0.1) inherit the raised bound. */
    @Bean("adminMagicLinkIpLimiter")
    fun adminMagicLinkIpLimiter(props: VerifolioProperties) = SlidingWindowRateLimiter(
        limit = props.auth.magicLinkIpLimit,
        window = Duration.ofMinutes(15),
    )
}
