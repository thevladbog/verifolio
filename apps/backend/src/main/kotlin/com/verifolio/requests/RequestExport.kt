package com.verifolio.requests

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Subject-scoped reference-request metadata for a DSR EXPORT package. Metadata only —
 * NO response letter text or structured answers. The recommender snapshot is nullable
 * because it is anonymized once the recommender's PII is erased.
 */
data class RequestExportData(
    val id: UUID,
    val recommenderName: String?,
    val recommenderEmail: String?,
    val purpose: String?,
    val status: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

/**
 * One-way export read port of the requests module. Two subject scopes:
 * account-holder (requester side, keyed by profile) and recommender (keyed by email).
 */
interface RequestExport {
    /** Requests the account-holder created (requester side). */
    fun forRequester(requesterProfileId: UUID): List<RequestExportData>

    /** Requests a recommender was invited to, matched on the snapshot email. */
    fun forRecommenderEmail(email: String): List<RequestExportData>
}
