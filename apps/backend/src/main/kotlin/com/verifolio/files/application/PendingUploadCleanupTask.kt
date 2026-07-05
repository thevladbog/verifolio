package com.verifolio.files.application

import com.verifolio.audit.AuditService
import com.verifolio.files.infrastructure.S3StorageAdapter
import com.verifolio.jooq.tables.references.FILE_OBJECT
import com.verifolio.platform.VerifolioProperties
import com.verifolio.workflows.RecurringTask
import org.jooq.DSLContext
import org.springframework.stereotype.Component
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

    private val log = org.slf4j.LoggerFactory.getLogger(PendingUploadCleanupTask::class.java)

    override val name = "pending-upload-cleanup"
    override val interval: Duration get() = props.workflows.cleanupInterval

    // No task-level transaction: the S3 delete must not run inside a DB transaction, and
    // each row commits independently. Bounded batch per tick; the rest waits for the next.
    override fun run() {
        val fo = FILE_OBJECT
        val cutoff = OffsetDateTime.now().minus(props.workflows.pendingUploadTtl)
        val stale = dsl.selectFrom(fo)
            .where(fo.STATUS.eq("PENDING").and(fo.CREATED_AT.lt(cutoff)))
            .orderBy(fo.CREATED_AT.asc())
            .limit(100)
            .fetch()

        stale.forEach { row ->
            // The row flips to DELETED only after the storage delete succeeded; a failed
            // delete leaves it PENDING so the next tick retries (no silent orphans).
            try {
                storage.delete(row.storageKey!!)
            } catch (e: Exception) {
                log.warn("Pending-upload cleanup: failed to delete object {}; will retry", row.storageKey, e)
                return@forEach
            }
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
