package com.verifolio.admin.application

import com.verifolio.documents.DocumentExport
import com.verifolio.identity.UserAdminView
import com.verifolio.privacy.UserPrivacySummary
import com.verifolio.profiles.ProfileExport
import com.verifolio.profiles.ProfileService
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Composes the read-only admin user card (spec §User views) from the owning modules' read ports —
 * identity (account + sessions), profiles (profile metadata), documents (counts), and privacy
 * (consents + DSR counts). Admin only reads; each module still owns its data. Metadata only:
 * NO document/letter/answer/file content is ever surfaced (support-without-content).
 */
@Service
internal class AdminUserService(
    private val userAdminView: UserAdminView,
    private val profileService: ProfileService,
    private val profileExport: ProfileExport,
    private val documentExport: DocumentExport,
    private val userPrivacySummary: UserPrivacySummary,
) {

    /**
     * The card for [userId], scoped to [region]. Returns null when the identity view has no such user
     * in this region (foreign-region or missing) → the controller renders 404. A document is counted
     * as "locked" when ANY of its versions has status `LOCKED`.
     */
    fun card(userId: UUID, region: String): AdminUserCard? {
        val identity = userAdminView.card(userId, region) ?: return null

        val profileId = profileService.requireProfileId(userId, identity.account.email)
        val profile = profileExport.forUser(userId)?.let {
            AdminUserCardProfile(displayName = it.displayName, legalName = it.legalName, preferredLocale = it.preferredLocale)
        }

        val documents = documentExport.forOwner(profileId)
        val documentCount = documents.size
        val lockedDocumentCount = documents.count { doc -> doc.versions.any { it.status == "LOCKED" } }

        val privacy = userPrivacySummary.forUser(userId)
        val consents = privacy.consents.map {
            AdminUserCardConsent(
                consentType = it.consentType,
                status = it.status,
                policyTextVersion = it.policyTextVersion,
                grantedAt = it.grantedAt,
                withdrawnAt = it.withdrawnAt,
                createdAt = it.createdAt,
            )
        }
        val sessions = identity.sessions.map {
            AdminUserCardSession(
                createdAt = it.createdAt,
                lastSeenAt = it.lastSeenAt,
                expiresAt = it.expiresAt,
                revokedAt = it.revokedAt,
            )
        }

        return AdminUserCard(
            account = AdminUserCardAccount(
                email = identity.account.email,
                region = identity.account.region,
                status = identity.account.status,
                createdAt = identity.account.createdAt,
                deletedAt = identity.account.deletedAt,
            ),
            profile = profile,
            documentCount = documentCount,
            lockedDocumentCount = lockedDocumentCount,
            consentCount = consents.size,
            sessionCount = sessions.size,
            consents = consents,
            sessions = sessions,
            dsrCountsByStatus = privacy.dsrCountsByStatus,
        )
    }
}

/** Composed admin user card — account + profile metadata, counts, consent/session history, DSR counts. */
data class AdminUserCard(
    val account: AdminUserCardAccount,
    val profile: AdminUserCardProfile?,
    val documentCount: Int,
    val lockedDocumentCount: Int,
    val consentCount: Int,
    val sessionCount: Int,
    val consents: List<AdminUserCardConsent>,
    val sessions: List<AdminUserCardSession>,
    val dsrCountsByStatus: Map<String, Int>,
)

data class AdminUserCardAccount(
    val email: String,
    val region: String,
    val status: String,
    val createdAt: OffsetDateTime,
    val deletedAt: OffsetDateTime?,
)

data class AdminUserCardProfile(
    val displayName: String,
    val legalName: String?,
    val preferredLocale: String,
)

data class AdminUserCardConsent(
    val consentType: String,
    val status: String,
    val policyTextVersion: String,
    val grantedAt: OffsetDateTime?,
    val withdrawnAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

data class AdminUserCardSession(
    val createdAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime?,
    val expiresAt: OffsetDateTime,
    val revokedAt: OffsetDateTime?,
)
