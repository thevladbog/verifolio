package com.verifolio.documents

import java.util.UUID

data class PublishDocumentCommand(
    val ownerProfileId: UUID,
    val requestId: UUID,
    val documentType: String,
    val approvedLetterText: String,
    /** Raw JSON of the structured answers. */
    val answersJson: String,
    val recommenderName: String,
    /** Recipient display name at publish time — snapshotted into the locked content. */
    val recipientName: String,
    val purpose: String?,
    val lockedByActorId: String,
)

data class PublishedVersion(
    val documentId: UUID,
    val versionId: UUID,
    val versionNumber: Int,
    val contentSha256: String,
    val pdfFileId: UUID,
    val pdfSha256: String,
)

data class AttachmentSpec(val fileObjectId: UUID, val type: String)

/**
 * Public API of the documents module.
 * Versions are created already LOCKED; there is no update path to a locked version
 * (docs/DATA_MODEL.md domain rule). Correction cycles produce the next version_number.
 */
interface DocumentPublisher {
    fun publishLockedVersion(cmd: PublishDocumentCommand): PublishedVersion

    /** Links READY evidence files to a version. Attachment rows are append-only. */
    fun attachFiles(versionId: UUID, attachments: List<AttachmentSpec>)
}
