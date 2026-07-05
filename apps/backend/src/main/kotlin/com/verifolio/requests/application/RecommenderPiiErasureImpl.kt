package com.verifolio.requests.application

import com.verifolio.audit.AuditService
import com.verifolio.files.FileUploads
import com.verifolio.jooq.tables.references.DOCUMENT_ATTACHMENT
import com.verifolio.jooq.tables.references.EMAIL_CONFIRMATION_CODE
import com.verifolio.jooq.tables.references.INVITATION_TOKEN
import com.verifolio.jooq.tables.references.RECOMMENDER_SESSION
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.jooq.tables.references.REFERENCE_RESPONSE
import com.verifolio.jooq.tables.references.RESPONSE_UPLOAD
import com.verifolio.requests.ErasureSummary
import com.verifolio.requests.RecommenderPiiErasure
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Implements the recommender-PII erasure matrix (privacy/DSR design, normative). All the
 * requests-side rows are erased in one transaction; the object-storage deletions are
 * delegated to the files module via [FileUploads.deleteUploadAsSystem] (the only path to
 * S3), each flipping its FileObject to DELETED only after the storage delete succeeds.
 * Idempotent on `recommender_pii_erased_at`.
 */
@Service
internal class RecommenderPiiErasureImpl(
    private val dsl: DSLContext,
    private val fileUploads: FileUploads,
    private val audit: AuditService,
) : RecommenderPiiErasure {

    @Transactional
    override fun eraseForRequest(requestId: UUID): ErasureSummary {
        val rr = REFERENCE_REQUEST
        val request = dsl.selectFrom(rr).where(rr.ID.eq(requestId)).forUpdate().fetchOne()
            ?: return ErasureSummary(requestId, 0, 0, 0, 0)
        // Idempotent: a request whose PII was already erased is a no-op with a zero summary.
        if (request.recommenderPiiErasedAt != null) {
            return ErasureSummary(requestId, 0, 0, 0, 0)
        }

        // Uploads whose file_object is NOT attached to a locked version: physically delete
        // via the files module (S3 first, status DELETED after), then drop the response_upload
        // row. Attached files are governed by retraction/tombstone rules, never erased here.
        val ru = RESPONSE_UPLOAD
        val da = DOCUMENT_ATTACHMENT
        val unattachedFileIds = dsl.select(ru.FILE_OBJECT_ID)
            .from(ru)
            .where(
                ru.REQUEST_ID.eq(requestId)
                    .and(ru.FILE_OBJECT_ID.notIn(dsl.select(da.FILE_OBJECT_ID).from(da))),
            )
            .fetch(ru.FILE_OBJECT_ID)
            .filterNotNull()
        unattachedFileIds.forEach { fileId ->
            fileUploads.deleteUploadAsSystem(fileId)
            dsl.deleteFrom(ru).where(ru.FILE_OBJECT_ID.eq(fileId)).execute()
        }
        val uploadsDeleted = unattachedFileIds.size

        // Recommender-authored operational data: drafts AND submitted responses go.
        val responsesDeleted = dsl.deleteFrom(REFERENCE_RESPONSE)
            .where(REFERENCE_RESPONSE.REQUEST_ID.eq(requestId))
            .execute()

        // Null the request snapshot columns and stamp the erasure marker.
        dsl.update(rr)
            .setNull(rr.RECOMMENDER_NAME)
            .setNull(rr.RECOMMENDER_EMAIL)
            .set(rr.RECOMMENDER_PII_ERASED_AT, OffsetDateTime.now())
            .set(rr.UPDATED_AT, OffsetDateTime.now())
            .where(rr.ID.eq(requestId))
            .execute()

        // invitation_token: null the email (tokens already revoked in terminal states); then
        // drop the confirmation codes tied to those tokens (they carry no PII themselves but
        // belong to the recommender-authentication trail for this request).
        val tokensScrubbed = dsl.update(INVITATION_TOKEN)
            .setNull(INVITATION_TOKEN.RECOMMENDER_EMAIL)
            .where(INVITATION_TOKEN.REQUEST_ID.eq(requestId))
            .execute()
        dsl.deleteFrom(EMAIL_CONFIRMATION_CODE)
            .where(
                EMAIL_CONFIRMATION_CODE.INVITATION_TOKEN_ID.`in`(
                    dsl.select(INVITATION_TOKEN.ID)
                        .from(INVITATION_TOKEN)
                        .where(INVITATION_TOKEN.REQUEST_ID.eq(requestId)),
                ),
            )
            .execute()

        // recommender_session rows carry the recommender email — delete them outright.
        val sessionsDeleted = dsl.deleteFrom(RECOMMENDER_SESSION)
            .where(RECOMMENDER_SESSION.REQUEST_ID.eq(requestId))
            .execute()

        audit.record(
            actorType = "SYSTEM",
            actorId = null,
            action = "RECOMMENDER_PII_ERASED",
            entityType = "REFERENCE_REQUEST",
            entityId = requestId.toString(),
            metadata = mapOf(
                "requestId" to requestId.toString(),
                "responsesDeleted" to responsesDeleted.toString(),
                "uploadsDeleted" to uploadsDeleted.toString(),
                "tokensScrubbed" to tokensScrubbed.toString(),
                "sessionsDeleted" to sessionsDeleted.toString(),
            ),
        )

        return ErasureSummary(
            requestId = requestId,
            responsesDeleted = responsesDeleted,
            uploadsDeleted = uploadsDeleted,
            tokensScrubbed = tokensScrubbed,
            sessionsDeleted = sessionsDeleted,
        )
    }
}
