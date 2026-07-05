package com.verifolio.files

import java.time.OffsetDateTime
import java.util.UUID

data class RequestedUpload(val fileId: UUID, val uploadUrl: String, val expiresAt: OffsetDateTime)

data class UploadOutcome(val status: String, val sha256: String?, val reason: String?)

/**
 * Public API: user-upload mechanics (docs/FILES_AND_STORAGE.md upload flow).
 * PENDING → constrained presigned PUT → confirm (synchronous validation) → READY|REJECTED.
 * The VALIDATING phase runs inside confirmUpload; the async antivirus pipeline is the
 * tracked Temporal follow-up. Domain authorization is the caller's responsibility.
 */
interface FileUploads {
    /**
     * Validates the declared MIME/size against the per-purpose policy, inserts a PENDING
     * FileObject under an opaque key, and returns a presigned PUT constrained to the
     * declared content type and length. Audits FILE_UPLOAD_REQUESTED.
     */
    fun requestUpload(
        purpose: String,
        filename: String,
        declaredMime: String,
        declaredSizeBytes: Long,
        actorId: String?,
    ): RequestedUpload

    /**
     * Synchronous validation: object present, actual size == declared, magic bytes match
     * the declared MIME, SHA-256 computed. READY on success (FILE_UPLOADED +
     * FILE_VALIDATED audited); REJECTED and the storage object deleted on failure.
     */
    fun confirmUpload(fileId: UUID): UploadOutcome

    /** Physical delete of an unattached PENDING/READY upload; status DELETED; audits FILE_DELETED. */
    fun deleteUpload(fileId: UUID)

    /**
     * Same physical delete as [deleteUpload] but attributed to the SYSTEM actor — for
     * automated erasure paths (recommender PII erasure). Status DELETED; audits FILE_DELETED
     * with actor SYSTEM. Only the files module ever talks to object storage.
     */
    fun deleteUploadAsSystem(fileId: UUID)
}
