package com.verifolio.privacy.application

import com.verifolio.documents.DocumentRetraction
import com.verifolio.requests.ConsentWithdrawal
import com.verifolio.requests.RecommenderRequestRef
import com.verifolio.requests.RecommenderPiiErasure
import com.verifolio.requests.RequestPublicView
import com.verifolio.verification.VerificationSignals
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Orchestrates the Flow-10 consent-withdrawal / retraction across the owning modules' public
 * APIs (privacy/DSR design §Retraction). Per reference request, in order:
 *  1. flip GRANTED consents → WITHDRAWN (requests);
 *  2. revoke the version + response verification signals (verification);
 *  3. stamp retracted_at on the versions (documents);
 *  4. erase the recommender's operational PII (requests → files).
 *
 * Each downstream call is independently transactional (the erasure step deletes S3 objects,
 * which must not run inside a long-held transaction — files-module precedent), so the executor
 * itself carries no outer transaction.
 */
@Component
internal class ConsentWithdrawalExecutor(
    private val consentWithdrawal: ConsentWithdrawal,
    private val verificationSignals: VerificationSignals,
    private val documentRetraction: DocumentRetraction,
    private val recommenderPiiErasure: RecommenderPiiErasure,
    private val requestPublicView: RequestPublicView,
) {

    private val log = LoggerFactory.getLogger(ConsentWithdrawalExecutor::class.java)

    fun execute(refs: List<RecommenderRequestRef>) {
        // Each reference request is processed independently: a failure on one (e.g. a transient
        // S3 error during erasure) is logged and skipped so the remaining refs still complete —
        // one ref must never abort the whole batch (RecommenderPiiErasureTask precedent).
        refs.forEach { ref ->
            try {
                consentWithdrawal.withdrawForRequest(ref.requestId, ref.recommenderContactId)

                documentRetraction.versionIdsForRequest(ref.requestId).forEach { versionId ->
                    verificationSignals.revokeAllForEntity("DOCUMENT_VERSION", versionId)
                }
                // Response-level signals (recipient/relationship/email confirmations) are revoked
                // too so a retracted recommendation carries no lingering active badge. Read before
                // erasure.
                requestPublicView.latestResponseId(ref.requestId)?.let { responseId ->
                    verificationSignals.revokeAllForEntity("REFERENCE_RESPONSE", responseId)
                }

                documentRetraction.markRetracted(ref.requestId)
                recommenderPiiErasure.eraseForRequest(ref.requestId)
            } catch (e: Exception) {
                log.warn("Consent-withdrawal execution failed for request {}; continuing", ref.requestId, e)
            }
        }
    }
}
