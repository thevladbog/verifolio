package com.verifolio.documents.application

import com.verifolio.audit.AuditService
import com.verifolio.documents.DocumentPublisher
import com.verifolio.documents.PublishDocumentCommand
import com.verifolio.documents.PublishedVersion
import com.verifolio.documents.domain.CanonicalJson
import com.verifolio.documents.domain.HtmlRenderer
import com.verifolio.files.FileStore
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

@Service
internal class DocumentPublisherImpl(
    private val dsl: DSLContext,
    private val fileStore: FileStore,
    private val pdfRenderer: PdfRenderer,
    private val audit: AuditService,
    private val objectMapper: ObjectMapper,
) : DocumentPublisher {

    @Transactional
    override fun publishLockedVersion(cmd: PublishDocumentCommand): PublishedVersion {
        val now = OffsetDateTime.now()
        val d = DOCUMENT

        val existing = dsl.selectFrom(d)
            .where(d.REQUEST_ID.eq(cmd.requestId))
            .forUpdate()
            .fetchOne()

        val documentId = existing?.id ?: run {
            val created = dsl.insertInto(d)
                .set(d.OWNER_PROFILE_ID, cmd.ownerProfileId)
                .set(d.REQUEST_ID, cmd.requestId)
                .set(d.TYPE, cmd.documentType)
                .set(d.STATUS, "ACTIVE")
                .returning(d.ID)
                .fetchOne()!!.id!!
            audit.record(
                actorType = "USER",
                actorId = cmd.lockedByActorId,
                action = "DOCUMENT_CREATED",
                entityType = "DOCUMENT",
                entityId = created.toString(),
                metadata = mapOf("type" to cmd.documentType, "requestId" to cmd.requestId.toString()),
            )
            created
        }

        val dv = DOCUMENT_VERSION
        val nextVersionNumber = (
            dsl.select(DSL.max(dv.VERSION_NUMBER)).from(dv)
                .where(dv.DOCUMENT_ID.eq(documentId))
                .fetchOne()?.value1() ?: 0
            ) + 1

        val contentJson = objectMapper.writeValueAsString(
            mapOf(
                "letterText" to cmd.approvedLetterText,
                "answers" to objectMapper.readTree(cmd.answersJson),
                "recommenderName" to cmd.recommenderName,
                "purpose" to cmd.purpose,
            ),
        )
        val canonical = CanonicalJson.canonicalize(contentJson)
        val contentSha256 = CanonicalJson.sha256Hex(canonical)

        val html = HtmlRenderer.render(
            letterText = cmd.approvedLetterText,
            recommenderName = cmd.recommenderName,
            purpose = cmd.purpose,
            lockedAtIso = now.toString(),
        )
        val pdfBytes = pdfRenderer.render(html)

        val versionId = UUID.randomUUID()
        val stored = fileStore.storeGeneratedPdf(
            ownerProfileId = cmd.ownerProfileId,
            documentId = documentId,
            versionId = versionId,
            filename = "reference-letter-v$nextVersionNumber.pdf",
            bytes = pdfBytes,
        )

        // Inserted already LOCKED — the version is never mutable.
        dsl.insertInto(dv)
            .set(dv.ID, versionId)
            .set(dv.DOCUMENT_ID, documentId)
            .set(dv.VERSION_NUMBER, nextVersionNumber)
            .set(dv.CONTENT_JSON, JSONB.valueOf(canonical))
            .set(dv.RENDERED_HTML, html)
            .set(dv.PDF_FILE_ID, stored.fileId)
            .set(dv.SHA256_HASH, contentSha256)
            .set(dv.STATUS, "LOCKED")
            .set(dv.LOCKED_AT, now)
            .set(dv.LOCKED_BY_ACTOR_ID, cmd.lockedByActorId)
            .execute()

        dsl.update(d)
            .set(d.CURRENT_VERSION_ID, versionId)
            .set(d.UPDATED_AT, now)
            .where(d.ID.eq(documentId))
            .execute()

        audit.record(
            actorType = "USER", actorId = cmd.lockedByActorId,
            action = "DOCUMENT_VERSION_CREATED",
            entityType = "DOCUMENT_VERSION", entityId = versionId.toString(),
            metadata = mapOf("documentId" to documentId.toString(), "versionNumber" to nextVersionNumber.toString()),
        )
        audit.record(
            actorType = "SYSTEM", actorId = null,
            action = "DOCUMENT_PDF_GENERATED",
            entityType = "DOCUMENT_VERSION", entityId = versionId.toString(),
            metadata = mapOf("pdfFileId" to stored.fileId.toString()),
        )
        audit.record(
            actorType = "USER", actorId = cmd.lockedByActorId,
            action = "DOCUMENT_VERSION_LOCKED",
            entityType = "DOCUMENT_VERSION", entityId = versionId.toString(),
            metadata = mapOf(
                "documentId" to documentId.toString(),
                "versionNumber" to nextVersionNumber.toString(),
                "contentSha256" to contentSha256,
            ),
        )

        return PublishedVersion(
            documentId = documentId,
            versionId = versionId,
            versionNumber = nextVersionNumber,
            contentSha256 = contentSha256,
            pdfFileId = stored.fileId,
            pdfSha256 = stored.sha256,
        )
    }
}
