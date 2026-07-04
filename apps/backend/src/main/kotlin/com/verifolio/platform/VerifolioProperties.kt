package com.verifolio.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "verifolio")
data class VerifolioProperties(
    val region: String,
    val auth: Auth,
    val mail: Mail = Mail(),
) {
    data class Auth(
        val tokenPepper: String,
        val magicLinkTtl: Duration,
        val sessionTtl: Duration,
        val frontendBaseUrl: String,
    )

    data class Mail(
        val from: String = "no-reply@verifolio.local",
    )
}
