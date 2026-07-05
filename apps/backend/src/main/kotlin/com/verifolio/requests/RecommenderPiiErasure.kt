package com.verifolio.requests

import java.util.UUID

/**
 * Counts of the rows touched by a single [RecommenderPiiErasure.eraseForRequest] call.
 * All zero when the request had already been erased (idempotent no-op).
 */
data class ErasureSummary(
    val requestId: UUID,
    val responsesDeleted: Int,
    val uploadsDeleted: Int,
    val tokensScrubbed: Int,
    val sessionsDeleted: Int,
)

/**
 * Public API of the requests module for operational erasure of a recommender's PII scoped
 * to one reference request (docs/PRIVACY_AND_DATA_CLASSIFICATION.md erasure model; the
 * erasure matrix in the privacy/DSR design is normative).
 *
 * Called by the privacy module: on the scheduled decline-grace sweep and as a step of the
 * hybrid DSR execution (consent withdrawal / recommender deletion). Owns exactly the
 * requests-side matrix rows; object-storage deletions are delegated to the files module.
 * Never touches recommender_contact, consent_record, document_attachment, locked
 * document_version rows, or audit_event.
 */
interface RecommenderPiiErasure {
    /**
     * Erases the recommender PII snapshot for [requestId]. Idempotent: if the request was
     * already erased (recommender_pii_erased_at set) it returns a zero summary and changes
     * nothing. Audits RECOMMENDER_PII_ERASED (SYSTEM) with the counts.
     */
    fun eraseForRequest(requestId: UUID): ErasureSummary
}
