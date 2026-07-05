package com.verifolio.requests

import java.util.UUID

/** A reference request that a given recommender email is the subject of, with its contact id. */
data class RecommenderRequestRef(val requestId: UUID, val recommenderContactId: UUID)

/**
 * Public API of the requests module for consent withdrawal (GDPR Art. 7(3), Flow 10). Consent
 * records live in requests-owned tables, so the flip is owned here; the privacy module
 * orchestrates the surrounding retraction/erasure steps via the documents/verification APIs.
 */
interface ConsentWithdrawal {
    /**
     * Flips every GRANTED consent_record row for [requestId] whose subject is
     * [recommenderContactId] to WITHDRAWN (stamping withdrawn_at). Returns the number of rows
     * flipped. Idempotent (already-withdrawn rows are skipped). Audits CONSENT_WITHDRAWN
     * (SYSTEM actor; metadata: ids + count).
     */
    fun withdrawForRequest(requestId: UUID, recommenderContactId: UUID): Int

    /**
     * Resolves the reference requests a recommender [email] is the subject of, matching either
     * the request's snapshot email or the linked contact's address-book email (case-insensitive).
     * Used by the privacy recommender DSR channel for subject resolution and execution scope.
     * Anti-enumeration: an empty list simply yields the 202 no-op at the channel boundary.
     */
    fun findRequestsByRecommenderEmail(email: String): List<RecommenderRequestRef>
}
