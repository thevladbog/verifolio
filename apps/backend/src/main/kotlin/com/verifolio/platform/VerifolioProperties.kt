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
    val storage: Storage = Storage(),
    val verification: Verification = Verification(),
    val publicPage: PublicPage = PublicPage(),
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
        val recommenderSessionTtl: Duration = Duration.ofHours(1),
        /** Per-IP magic-link request window; raised in integration tests (shared 127.0.0.1). */
        val magicLinkIpLimit: Int = 100,
        val emailConfirmationTtl: Duration = Duration.ofMinutes(10),
        val emailConfirmationLimit: Int = 3,
        val emailConfirmationWindow: Duration = Duration.ofMinutes(15),
    )

    data class Mail(
        val from: String = "no-reply@verifolio.local",
    )

    data class Consents(
        val requesterAttestation: ConsentText = ConsentText(textId = "local-requester-attestation", version = 1),
        val processing: ConsentText = ConsentText(textId = "local-processing", version = 1),
        val crossBorderTransfer: ConsentText = ConsentText(textId = "local-cross-border", version = 1),
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

    data class Storage(
        val endpoint: String = "http://localhost:9000",
        val regionName: String = "local",
        val bucket: String = "verifolio-local",
        val accessKey: String = "minioadmin",
        val secretKey: String = "minioadmin",
        val presignedTtl: Duration = Duration.ofMinutes(5),
        val pathStyle: Boolean = true,
    )

    data class Verification(
        val freeEmailDomains: List<String> = listOf(
            "gmail.com", "googlemail.com", "yahoo.com", "hotmail.com", "outlook.com",
            "mail.ru", "yandex.ru", "icloud.com", "proton.me", "protonmail.com",
        ),
    )

    data class PublicPage(
        /** 0.0–1.0 share of public page views written to the audit log (views are sampled; downloads always audited). */
        val viewAuditSampleRate: Double = 1.0,
    ) {
        init {
            require(viewAuditSampleRate in 0.0..1.0) {
                "verifolio.public-page.view-audit-sample-rate must be within 0.0..1.0"
            }
        }
    }
}
