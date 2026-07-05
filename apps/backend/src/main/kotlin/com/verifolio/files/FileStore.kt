package com.verifolio.files

import java.time.OffsetDateTime
import java.util.UUID

data class StoredFile(val fileId: UUID, val sha256: String, val sizeBytes: Long)

data class DownloadLink(val url: String, val expiresAt: OffsetDateTime)

/**
 * Public API of the files module — the ONLY path to object storage
 * (docs/FILES_AND_STORAGE.md: no S3 calls outside this module, no public URLs).
 */
interface FileStore {
    /**
     * Stores backend-generated PDF bytes under an opaque region-scoped key and inserts the
     * FileObject directly as READY (the backend produced the bytes; the async validation
     * pipeline applies to user uploads only). Audited (FILE_UPLOADED).
     */
    fun storeGeneratedPdf(
        ownerProfileId: UUID,
        documentId: UUID,
        versionId: UUID,
        filename: String,
        bytes: ByteArray,
    ): StoredFile

    /**
     * Short-lived presigned GET for a READY file. Domain authorization is the caller's
     * responsibility; this only enforces file status.
     */
    fun presignedDownloadUrl(fileId: UUID): DownloadLink
}
