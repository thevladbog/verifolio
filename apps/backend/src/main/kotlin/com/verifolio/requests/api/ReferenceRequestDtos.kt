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
    /** Decline reason category (enum value); set only for DECLINED requests, and only when the recommender chose one. */
    val declinedReason: String?,
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

/** Owner-visible view of the latest submitted response (Flow 4 review, pre-accept). */
data class SubmittedResponseView(
    val approvedLetterText: String?,
    val answers: Map<String, Any?>,
    val submittedAt: String,
    val recipientConfirmed: Boolean,
    val relationshipConfirmed: Boolean,
    /** Metadata only — no pre-accept file downloads (spec: frontend-api-tails §1). */
    val uploads: List<UploadMeta>,
)

data class UploadMeta(
    val id: String,
    val kind: String,
    val contentType: String,
    val sizeBytes: Long,
    val sharedPublicly: Boolean,
    /** For DETACHED_SIGNATURE: the upload the signature covers. */
    val targetUploadId: String?,
)
