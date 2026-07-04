package com.verifolio.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "verifolio")
data class VerifolioProperties(
    val region: String,
    val auth: Auth,
    val mail: Mail = Mail(),
) {
    init {
        require(region == "local" || auth.tokenPepper != "local-dev-pepper-change-me") {
            "verifolio.auth.token-pepper must be overridden outside the local region"
        }
    }

    data class Auth(
        val tokenPepper: String,
        val magicLinkTtl: Duration,
        val sessionTtl: Duration,
        val frontendBaseUrl: String,
        val cookieSecure: Boolean = true,
    )

    data class Mail(
        val from: String = "no-reply@verifolio.local",
    )
}
