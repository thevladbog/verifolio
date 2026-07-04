package com.verifolio.requests.api

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
