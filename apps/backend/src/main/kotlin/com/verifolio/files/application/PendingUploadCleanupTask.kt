package com.verifolio.files.application

import com.verifolio.audit.AuditService
import com.verifolio.files.infrastructure.S3StorageAdapter
import com.verifolio.jooq.tables.references.FILE_OBJECT
import com.verifolio.platform.VerifolioProperties
import com.verifolio.workflows.RecurringTask
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Sweeps PENDING uploads whose presigned PUT window is long gone
 * (docs/FILES_AND_STORAGE.md: PENDING objects have a TTL).
 */
@Component
internal class PendingUploadCleanupTask(
    private val dsl: DSLContext,
    private val storage: S3StorageAdapter,
    private val audit: AuditService,
    private val props: VerifolioProperties,
) : RecurringTask {

    override val name = "pending-upload-cleanup"
    override val interval: Duration get() = props.workflows.cleanupInterval

    @Transactional
    override fun run() {
        val fo = FILE_OBJECT
        val cutoff = OffsetDateTime.now().minus(props.workflows.pendingUploadTtl)
        val stale = dsl.selectFrom(fo)
            .where(fo.STATUS.eq("PENDING").and(fo.CREATED_AT.lt(cutoff)))
            .fetch()

        stale.forEach { row ->
            runCatching { storage.delete(row.storageKey!!) }
            val updated = dsl.update(fo)
                .set(fo.STATUS, "DELETED")
                .set(fo.DELETED_AT, OffsetDateTime.now())
                .where(fo.ID.eq(row.id).and(fo.STATUS.eq("PENDING")))
                .execute()
            if (updated > 0) {
                audit.record(
                    actorType = "SYSTEM",
                    actorId = null,
                    action = "FILE_DELETED",
                    entityType = "FILE_OBJECT",
                    entityId = row.id.toString(),
                    metadata = mapOf("reason" to "pending_ttl", "purpose" to row.purpose!!),
                )
            }
        }
    }
}
