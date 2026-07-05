package com.verifolio.requests.api

import com.verifolio.requests.domain.DeclineReason
import jakarta.validation.constraints.NotBlank

data class InvitationPreviewResponse(
    val requesterName: String,
    val purpose: String?,
    val templateName: String,
    val recommenderEmailMasked: String,
    val status: String,
)

data class ConfirmEmailRequest(
    @field:NotBlank val code: String,
)

data class ConfirmEmailResponse(
    val status: String,
)

/** Optional one-click decline body; absent body behaves exactly as before. */
data class DeclineRequest(
    val reasonCategory: DeclineReason? = null,
)
