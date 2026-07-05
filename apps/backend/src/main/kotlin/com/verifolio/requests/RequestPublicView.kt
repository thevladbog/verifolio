package com.verifolio.requests

import java.time.OffsetDateTime
import java.util.UUID

data class RequestPublicInfo(
    /** Snapshot taken at request creation — labeled "stated by recommender" on display. */
    val recommenderName: String,
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
}
