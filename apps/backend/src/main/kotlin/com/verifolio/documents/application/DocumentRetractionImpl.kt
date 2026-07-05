package com.verifolio.documents.application

import com.verifolio.audit.AuditService
import com.verifolio.documents.DocumentRetraction
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Sets `retracted_at` on the request's document versions. Retraction ≠ deletion: the locked
 * content stays intact and readable — this only stamps the marker used to render the
 * "recommendation retracted" banner. Idempotent (already-retracted versions are skipped by
 * the `retracted_at IS NULL` guard). Non-negotiable: no other locked-version column changes.
 */
@Service
internal class DocumentRetractionImpl(
    private val dsl: DSLContext,
    private val audit: AuditService,
) : DocumentRetraction {

    @Transactional
    override fun markRetracted(requestId: UUID): Int {
        val d = DOCUMENT
        val dv = DOCUMENT_VERSION
        val now = OffsetDateTime.now()

        // The request's versions that are not yet retracted and not tombstoned (a tombstoned
        // version has no content to retract). Idempotent: a second call finds none.
        val affected = dsl.update(dv)
            .set(dv.RETRACTED_AT, now)
            .where(
                dv.DOCUMENT_ID.`in`(
                    dsl.select(d.ID).from(d).where(d.REQUEST_ID.eq(requestId)),
                )
                    .and(dv.RETRACTED_AT.isNull)
                    .and(dv.STATUS.ne("TOMBSTONED")),
            )
            .execute()

        if (affected > 0) {
            audit.record(
                actorType = "SYSTEM",
                actorId = null,
                action = "RECOMMENDATION_RETRACTED",
                entityType = "REFERENCE_REQUEST",
                entityId = requestId.toString(),
                metadata = mapOf("requestId" to requestId.toString(), "count" to affected.toString()),
            )
        }
        return affected
    }
}
