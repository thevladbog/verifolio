package com.verifolio.documents.api

data class DocumentVersionResponse(
    val versionNumber: Int,
    val status: String,
    val sha256Hash: String,
    val lockedAt: String,
    val createdAt: String,
)

data class DocumentResponse(
    val id: String,
    val requestId: String?,
    val type: String,
    val status: String,
    val currentVersionNumber: Int?,
    val createdAt: String,
    val updatedAt: String?,
)

data class DocumentDetailResponse(
    val id: String,
    val requestId: String?,
    val type: String,
    val status: String,
    val currentVersionNumber: Int?,
    val versions: List<DocumentVersionResponse>,
    val createdAt: String,
    val updatedAt: String?,
)

data class DocumentListResponse(
    val items: List<DocumentResponse>,
    val nextCursor: String?,
)

data class DownloadLinkResponse(
    val url: String,
    val expiresAt: String,
)
