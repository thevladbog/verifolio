package com.verifolio.requests

import java.time.OffsetDateTime
import java.util.UUID

data class RequestPublicInfo(
    /**
     * Snapshot taken at request creation — labeled "stated by recommender" on display.
     * Null once the recommender's PII has been erased (e.g. after a retraction), so the
     * public page must omit the recommender block rather than expose an empty name.
     */
    val recommenderName: String?,
    val relationshipType: String?,
    val purpose: String?,
    val requestCreatedAt: OffsetDateTime,
    val responseSubmittedAt: OffsetDateTime?,
)

/**
 * Public read model of the requests module for the verification page
 * (docs/PUBLIC_VERIFICATION_PAGE.md). Contains no emails and no letter content.
 */
interface RequestPublicView {
    fun forRequest(requestId: UUID): RequestPublicInfo?

    /** Id of the latest submitted response — the entity response-level signals are attached to. */
    fun latestResponseId(requestId: UUID): UUID?
}
