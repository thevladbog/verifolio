package com.verifolio.privacy.infrastructure

import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.VerifolioProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class PrivacyBeans {
    /** Per-email verification-code limit for the recommender DSR channel (3/15min). */
    @Bean("dsrCodeLimiter")
    fun dsrCodeLimiter(props: VerifolioProperties) =
        SlidingWindowRateLimiter(props.privacy.codeLimit, props.privacy.codeLimitWindow)

    /** Per-IP limit for the public recommender DSR intake (100/15min; raised in tests). */
    @Bean("dsrRecommenderIpLimiter")
    fun dsrRecommenderIpLimiter(props: VerifolioProperties) =
        SlidingWindowRateLimiter(props.privacy.recommenderIpLimit, props.privacy.recommenderIpWindow)
}
