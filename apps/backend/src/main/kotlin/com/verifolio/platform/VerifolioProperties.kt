package com.verifolio.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "verifolio")
data class VerifolioProperties(
    val region: String,
    val auth: Auth,
    val mail: Mail = Mail(),
    val consents: Consents = Consents(),
    val requests: Requests = Requests(),
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

    data class Consents(
        val requesterAttestation: ConsentText = ConsentText(textId = "local-requester-attestation", version = 1),
    )

    /** Versioned consent text identifier per region policy (docs/REGION_POLICIES.md). */
    data class ConsentText(val textId: String, val version: Int) {
        /** Stored in ConsentRecord.policy_text_version, e.g. "local-requester-attestation:1". */
        val versionedId: String get() = "$textId:$version"
    }

    data class Requests(
        val expiry: Duration = Duration.ofDays(21),
        val sendLimitPerRecommender: Int = 5,
        val sendLimitWindow: Duration = Duration.ofDays(1),
    )
}
