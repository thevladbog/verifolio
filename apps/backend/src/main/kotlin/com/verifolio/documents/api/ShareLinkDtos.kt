package com.verifolio.documents.api

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class CreateShareLinkRequest(
    /** null = the link never expires. */
    @field:Min(1) @field:Max(365) val expiresInDays: Int? = null,
)

data class ShareLinkCreatedResponse(
    val id: String,
    /** Contains the raw token — shown exactly once, never retrievable again. */
    val url: String,
    val versionNumber: Int,
    val expiresAt: String?,
    val createdAt: String,
)

data class ShareLinkResponse(
    val id: String,
    val versionNumber: Int,
    val expiresAt: String?,
    val revokedAt: String?,
    val createdAt: String,
)

data class ShareLinkListResponse(
    val items: List<ShareLinkResponse>,
)
