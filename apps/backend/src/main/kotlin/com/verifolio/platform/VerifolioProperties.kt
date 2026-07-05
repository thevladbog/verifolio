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
    val workflows: Workflows = Workflows(),
    val privacy: Privacy = Privacy(),
    val admin: Admin = Admin(),
) {
    init {
        require(region == "local" || auth.tokenPepper != "local-dev-pepper-change-me") {
            "verifolio.auth.token-pepper must be overridden outside the local region"
        }
        require(region == "local" || admin.totpSecretKey != Admin.LOCAL_DEV_TOTP_SECRET_KEY) {
            "verifolio.admin.totp-secret-key must be overridden outside the local region"
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
        val publicSharing: ConsentText = ConsentText(textId = "local-public-sharing", version = 1),
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
        val maxUploadBytes: Long = 15L * 1024 * 1024,
        val uploadUrlTtl: Duration = Duration.ofMinutes(10),
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

    /** DB-scheduler fallback per ADR-0005; Temporal remains the target engine. */
    data class Workflows(
        val enabled: Boolean = true,
        val tickInterval: Duration = Duration.ofMinutes(1),
        /** Reminder offsets from sent_at; the last one carries the expiration warning. */
        val reminderOffsets: List<Duration> = listOf(Duration.ofDays(3), Duration.ofDays(7), Duration.ofDays(14)),
        val cleanupInterval: Duration = Duration.ofHours(1),
        val pendingUploadTtl: Duration = Duration.ofHours(24),
    ) {
        init {
            require(reminderOffsets == reminderOffsets.sorted()) {
                "verifolio.workflows.reminder-offsets must be sorted ascending"
            }
        }
    }

    /** Data-subject-request handling (docs/PRIVACY_AND_DATA_CLASSIFICATION.md §246-291). */
    data class Privacy(
        /** DSR response deadline per cell; local default is the GDPR 30-day window. */
        val sla: Duration = Duration.ofDays(30),
        /** Abuse-investigation window before declined requests' recommender PII is erased (Flow 9). */
        val declineErasureGrace: Duration = Duration.ofHours(24),
        /** Per-email verification-code window for the recommender DSR channel. */
        val codeLimit: Int = 3,
        val codeLimitWindow: Duration = Duration.ofMinutes(15),
        /** Per-IP window for the public recommender DSR intake; raised in integration tests. */
        val recommenderIpLimit: Int = 100,
        val recommenderIpWindow: Duration = Duration.ofMinutes(15),
    )

    /**
     * Admin foundation config (docs/superpowers/specs/2026-07-05-admin-foundation-design.md).
     * @param bootstrapEmails config-driven, idempotent SUPERADMIN bootstrap on startup;
     *   empty = no bootstrap (prod sets it per cell).
     * @param totpSecretKey base64-encoded 32-byte AES-256-GCM key encrypting admin TOTP
     *   secrets at rest (AdminTotpCipher). The local default ships for dev only; real cells
     *   set their own (validated non-default when region != local, mirroring the pepper rule).
     */
    data class Admin(
        val bootstrapEmails: List<String> = emptyList(),
        val totpSecretKey: String = LOCAL_DEV_TOTP_SECRET_KEY,
    ) {
        companion object {
            /** DEV ONLY — base64 of a 32-byte "verifolio-local-dev-totp-key!!!!" key. */
            const val LOCAL_DEV_TOTP_SECRET_KEY = "local-dev-unsafe-totp-key"
        }
    }
}
