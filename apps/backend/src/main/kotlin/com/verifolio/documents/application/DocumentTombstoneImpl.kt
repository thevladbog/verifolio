package com.verifolio.documents.application

import com.verifolio.audit.AuditService
import com.verifolio.documents.DocumentTombstone
import com.verifolio.files.FileStore
import com.verifolio.files.FileUploads
import com.verifolio.jooq.tables.references.DOCUMENT_ATTACHMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The single sanctioned content-erasing mutation of a locked version. Object storage is
 * cleared first (generated PDF via [FileStore], attachment uploads via [FileUploads] — the
 * files module is the only path to S3), then the content columns are nulled and the status
 * flips to TOMBSTONED. `sha256_hash`, `version_number` and `locked_at` are deliberately
 * left untouched: the integrity anchors must survive so the erasure stays provable.
 * Idempotent — an already-TOMBSTONED version is a no-op.
 */
@Service
internal class DocumentTombstoneImpl(
    private val dsl: DSLContext,
    private val fileStore: FileStore,
    private val fileUploads: FileUploads,
    private val audit: AuditService,
) : DocumentTombstone {

    @Transactional
    override fun tombstone(versionId: UUID) {
        val dv = DOCUMENT_VERSION
        val version = dsl.selectFrom(dv).where(dv.ID.eq(versionId)).forUpdate().fetchOne() ?: return
        // Idempotent: content already gone, objects already deleted.
        if (version.status == "TOMBSTONED") return

        // Storage first (S3 delete then FileObject → DELETED), DB content-null after.
        version.pdfFileId?.let { fileStore.deleteGeneratedAsSystem(it) }
        val da = DOCUMENT_ATTACHMENT
        val attachmentFileIds = dsl.select(da.FILE_OBJECT_ID).from(da)
            .where(da.DOCUMENT_VERSION_ID.eq(versionId))
            .fetch(da.FILE_OBJECT_ID)
            .filterNotNull()
        attachmentFileIds.forEach { fileUploads.deleteUploadAsSystem(it) }

        // Null content, flip status — integrity anchors (sha256/version_number/locked_at) stay.
        dsl.update(dv)
            .setNull(dv.CONTENT_JSON)
            .setNull(dv.RENDERED_HTML)
            .set(dv.STATUS, "TOMBSTONED")
            .set(dv.TOMBSTONED_AT, OffsetDateTime.now())
            .where(dv.ID.eq(versionId))
            .execute()

        audit.record(
            actorType = "SYSTEM",
            actorId = null,
            action = "DOCUMENT_VERSION_TOMBSTONED",
            entityType = "DOCUMENT_VERSION",
            entityId = versionId.toString(),
            metadata = mapOf("versionId" to versionId.toString()),
        )
    }
}
