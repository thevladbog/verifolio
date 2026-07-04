package com.verifolio.platform.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "verifolio")
data class VerifolioProperties(
    val region: String,
    val auth: Auth,
) {
    data class Auth(
        val tokenPepper: String,
        val magicLinkTtl: Duration,
        val sessionTtl: Duration,
        val frontendBaseUrl: String,
    )
}
