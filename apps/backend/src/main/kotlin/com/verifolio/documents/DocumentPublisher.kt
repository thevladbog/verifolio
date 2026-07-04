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

/**
 * Public API of the documents module.
 * Versions are created already LOCKED; there is no update path to a locked version
 * (docs/DATA_MODEL.md domain rule). Correction cycles produce the next version_number.
 */
interface DocumentPublisher {
    fun publishLockedVersion(cmd: PublishDocumentCommand): PublishedVersion
}
