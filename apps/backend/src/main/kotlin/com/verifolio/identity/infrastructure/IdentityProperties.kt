package com.verifolio.identity.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Identity-scoped view of the verifolio.* config tree.
 * Keeps the identity module free of cross-module dependencies on platform internals.
 */
@ConfigurationProperties(prefix = "verifolio")
data class IdentityProperties(
    val region: String,
    val auth: Auth,
) {
    data class Auth(
        val tokenPepper: String,
        val magicLinkTtl: Duration,
        val frontendBaseUrl: String,
    )
}
