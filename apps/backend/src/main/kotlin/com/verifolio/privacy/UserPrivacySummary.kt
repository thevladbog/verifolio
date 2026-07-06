package com.verifolio.privacy

import java.time.OffsetDateTime
import java.util.UUID

/**
 * One consent-record row for a user (privacy-owned; consent is retained even after account erasure).
 * Metadata only — consent type, status, the policy text version, and lifecycle timestamps.
 *
 * `declinedAt` is carried (alongside grantedAt/withdrawnAt) so this single read can back BOTH the
 * admin user card AND the DSR EXPORT package's consent section without losing any timestamp (DRY).
 */
data class ConsentSummary(
    val consentType: String,
    val status: String,
    val policyTextVersion: String,
    val grantedAt: OffsetDateTime?,
    val declinedAt: OffsetDateTime?,
    val withdrawnAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

/**
 * A user's privacy footprint for the admin card (spec §User views): their consent records and a
 * count of their data-subject requests grouped by status. Metadata only — no request resolution
 * content, no PII beyond the user's own consent metadata.
 */
data class UserPrivacyData(
    val consents: List<ConsentSummary>,
    val dsrCountsByStatus: Map<String, Int>,
)

/**
 * Privacy-owned read port exposing a user's consent history + DSR counts. Owner-scoped (by user_id).
 * Consumed by admin's user card (spec §User views) and by the DSR EXPORT executor's consent section
 * (the previously-inline consent read is extracted here so both consumers share one query — DRY).
 */
interface UserPrivacySummary {
    fun forUser(userId: UUID): UserPrivacyData
}
