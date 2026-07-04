package com.verifolio.requests.api

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateReferenceRequestRequest(
    @field:NotNull val recommenderContactId: UUID?,
    @field:NotNull val templateId: UUID?,
    @field:Size(max = 2000) val purpose: String? = null,
    val verbalConsentAttested: Boolean = false,
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
