package com.verifolio.admin.api

import com.verifolio.admin.application.AdminUserCard
import com.verifolio.identity.UserAdminSummary

/** One user list row (metadata only — no credentials/tokens/session hashes). */
data class AdminUserListItemResponse(
    val id: String,
    val email: String,
    val displayName: String?,
    val region: String,
    val status: String,
    val createdAt: String,
) {
    companion object {
        fun from(s: UserAdminSummary) = AdminUserListItemResponse(
            id = s.id.toString(),
            email = s.email,
            displayName = s.displayName,
            region = s.region,
            status = s.status,
            createdAt = s.createdAt.toString(),
        )
    }
}

/** A keyset page of user list rows. */
data class AdminUserListResponse(
    val items: List<AdminUserListItemResponse>,
    val nextCursor: String?,
)

/** Account metadata for the card. */
data class AdminUserAccountResponse(
    val email: String,
    val region: String,
    val status: String,
    val createdAt: String,
    val deletedAt: String?,
)

/** Profile metadata for the card (null when the user has no profile). */
data class AdminUserProfileResponse(
    val displayName: String,
    val legalName: String?,
    val preferredLocale: String,
)

/** One consent record (metadata only). */
data class AdminUserConsentResponse(
    val consentType: String,
    val status: String,
    val policyTextVersion: String,
    val grantedAt: String?,
    val withdrawnAt: String?,
    val createdAt: String,
)

/** One session (timestamps only — NO ip/user-agent hashes, no device/location). */
data class AdminUserSessionResponse(
    val createdAt: String,
    val lastSeenAt: String?,
    val expiresAt: String,
    val revokedAt: String?,
)

/**
 * The read-only admin user card. Metadata only — account, profile, counts, and consent/session
 * history; NEVER document/letter/answer/file content (support-without-content).
 */
data class AdminUserCardResponse(
    val account: AdminUserAccountResponse,
    val profile: AdminUserProfileResponse?,
    val documentCount: Int,
    val lockedDocumentCount: Int,
    val consentCount: Int,
    val sessionCount: Int,
    val consents: List<AdminUserConsentResponse>,
    val sessions: List<AdminUserSessionResponse>,
    val dsrCountsByStatus: Map<String, Int>,
) {
    companion object {
        fun from(c: AdminUserCard) = AdminUserCardResponse(
            account = AdminUserAccountResponse(
                email = c.account.email,
                region = c.account.region,
                status = c.account.status,
                createdAt = c.account.createdAt.toString(),
                deletedAt = c.account.deletedAt?.toString(),
            ),
            profile = c.profile?.let {
                AdminUserProfileResponse(
                    displayName = it.displayName,
                    legalName = it.legalName,
                    preferredLocale = it.preferredLocale,
                )
            },
            documentCount = c.documentCount,
            lockedDocumentCount = c.lockedDocumentCount,
            consentCount = c.consentCount,
            sessionCount = c.sessionCount,
            consents = c.consents.map {
                AdminUserConsentResponse(
                    consentType = it.consentType,
                    status = it.status,
                    policyTextVersion = it.policyTextVersion,
                    grantedAt = it.grantedAt?.toString(),
                    withdrawnAt = it.withdrawnAt?.toString(),
                    createdAt = it.createdAt.toString(),
                )
            },
            sessions = c.sessions.map {
                AdminUserSessionResponse(
                    createdAt = it.createdAt.toString(),
                    lastSeenAt = it.lastSeenAt?.toString(),
                    expiresAt = it.expiresAt.toString(),
                    revokedAt = it.revokedAt?.toString(),
                )
            },
            dsrCountsByStatus = c.dsrCountsByStatus,
        )
    }
}
