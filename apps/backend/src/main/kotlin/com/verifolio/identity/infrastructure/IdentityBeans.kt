package com.verifolio.identity.infrastructure

import com.verifolio.platform.TokenHasher
import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.VerifolioProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
internal class IdentityBeans {
    @Bean
    fun tokenHasher(props: VerifolioProperties) = TokenHasher(props.auth.tokenPepper)

    @Bean("magicLinkEmailLimiter")
    fun magicLinkEmailLimiter() = SlidingWindowRateLimiter(limit = 5, window = Duration.ofMinutes(15))

    @Bean("magicLinkIpLimiter")
    fun magicLinkIpLimiter(props: VerifolioProperties) = SlidingWindowRateLimiter(
        // Default 100/15min; integration tests raise it (all requests share 127.0.0.1).
        limit = props.auth.magicLinkIpLimit,
        window = Duration.ofMinutes(15),
    )
}
