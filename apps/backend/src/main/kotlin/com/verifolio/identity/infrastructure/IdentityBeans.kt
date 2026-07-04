package com.verifolio.identity.infrastructure

import com.verifolio.identity.domain.TokenHasher
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
    fun magicLinkIpLimiter() = SlidingWindowRateLimiter(
        // 100 is high enough for local/test use; production tuning tracked for the regional deployment task.
        limit = 100,
        window = Duration.ofMinutes(15),
    )
}
