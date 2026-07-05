package com.verifolio.requests.api

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateReferenceRequestRequest(
    @field:NotNull val recommenderContactId: UUID?,
    @field:NotNull val templateId: UUID?,
    @field:Size(max = 2000) val purpose: String? = null,
    /** Must be sent and true — the blocking verbal-consent attestation checkbox (USER_FLOWS.md Flow 2). */
    @field:NotNull val verbalConsentAttested: Boolean?,
)

data class ReferenceRequestResponse(
    val id: String,
    val recommenderContactId: String,
    val templateId: String,
    val purpose: String?,
    val status: String,
    val expiresAt: String,
    val createdAt: String,
    val updatedAt: String?,
)

data class ReferenceRequestListResponse(
    val items: List<ReferenceRequestResponse>,
    val nextCursor: String?,
)

data class RequestCorrectionRequest(
    /** Included in the email to the recommender only; never persisted. */
    @field:Size(max = 2000) val message: String? = null,
)

data class AcceptResponse(
    val request: ReferenceRequestResponse,
    val documentId: String,
)
