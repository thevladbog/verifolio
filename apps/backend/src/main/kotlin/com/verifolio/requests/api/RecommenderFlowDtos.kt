package com.verifolio.requests.api

import com.verifolio.requests.domain.DeclineReason
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

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
    /** Optional decline category; applied only when the decision is DECLINED. */
    val reasonCategory: DeclineReason? = null,
)

data class DraftRequest(
    @field:NotNull val answersJson: Map<String, Any?>?,
    @field:Size(max = 20000) val approvedLetterText: String? = null,
)

data class SubmitResponseRequest(
    /** Bounded: this text is rendered synchronously into the PDF at acceptance. */
    @field:NotBlank @field:Size(max = 20000) val approvedLetterText: String?,
    @field:Size(max = 2000) val confirmationText: String? = null,
    @field:NotNull val recipientConfirmed: Boolean?,
    @field:NotNull val relationshipConfirmed: Boolean?,
    val answersJson: Map<String, Any?>? = null,
)

data class RecommenderStatusResponse(
    val status: String,
)
