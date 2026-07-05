package com.verifolio.verification.infrastructure

import com.verifolio.platform.SlidingWindowRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
internal class VerificationBeans {
    /** Public page endpoints: generous per-IP window against scraping/enumeration. */
    @Bean("verificationPageIpLimiter")
    fun verificationPageIpLimiter() = SlidingWindowRateLimiter(limit = 300, window = Duration.ofMinutes(15))
}
