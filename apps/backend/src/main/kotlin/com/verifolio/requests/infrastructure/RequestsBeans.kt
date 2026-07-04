package com.verifolio.requests.infrastructure

import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.VerifolioProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class RequestsBeans {
    /** Global per-recommender-email limit across ALL requesters (docs/RECOMMENDER_EXPERIENCE.md anti-spam). */
    @Bean("referenceRequestSendLimiter")
    fun referenceRequestSendLimiter(props: VerifolioProperties) =
        SlidingWindowRateLimiter(props.requests.sendLimitPerRecommender, props.requests.sendLimitWindow)
}
