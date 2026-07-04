package com.verifolio.requests.api

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class ConsentTextRef(val textId: String, val version: Int)

data class ConsentTextsDto(
    val processing: ConsentTextRef,
    val crossBorderTransfer: ConsentTextRef,
)

data class DraftDto(
    val answersJson: Map<String, Any?>,
    val approvedLetterText: String?,
    val updatedAt: String,
)

data class RecommenderRequestContext(
    val status: String,
    val requesterName: String,
    val purpose: String?,
    val templateName: String,
    val questionSchema: Map<String, Any?>,
    val consents: ConsentTextsDto,
    val draft: DraftDto?,
)

data class ConsentDecisionRequest(
    @field:NotNull val accepted: Boolean?,
    val crossBorderAccepted: Boolean? = null,
)

data class DraftRequest(
    @field:NotNull val answersJson: Map<String, Any?>?,
    val approvedLetterText: String? = null,
)

data class SubmitResponseRequest(
    @field:NotBlank val approvedLetterText: String?,
    val confirmationText: String? = null,
    @field:NotNull val recipientConfirmed: Boolean?,
    @field:NotNull val relationshipConfirmed: Boolean?,
    val answersJson: Map<String, Any?>? = null,
)

data class RecommenderStatusResponse(
    val status: String,
)
