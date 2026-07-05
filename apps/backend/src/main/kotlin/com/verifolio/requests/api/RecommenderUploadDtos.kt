package com.verifolio.requests.api

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.util.UUID

data class CreateUploadRequest(
    @field:NotNull val kind: UploadKind?,
    @field:NotBlank val filename: String?,
    @field:NotBlank val mimeType: String?,
    @field:NotNull @field:Positive val sizeBytes: Long?,
    /** Per-upload "may be shared publicly" toggle (RECOMMENDER_PUBLIC_SHARING_CONSENT). */
    val sharedPublicly: Boolean = false,
    /** Required for DETACHED_SIGNATURE: the READY upload the signature covers. */
    val targetUploadId: UUID? = null,
)

enum class UploadKind { SCAN, SIGNED_PDF, DETACHED_SIGNATURE, ATTACHMENT }

data class UploadCreatedResponse(
    val uploadId: String,
    val fileId: String,
    val uploadUrl: String,
    val expiresAt: String,
)

data class ConfirmUploadResponse(
    val status: String,
    val sha256: String?,
    val reason: String?,
)

data class UploadResponse(
    val uploadId: String,
    val kind: String,
    val filename: String,
    val status: String,
    val sharedPublicly: Boolean,
    val targetUploadId: String?,
)

data class UploadListResponse(val items: List<UploadResponse>)
