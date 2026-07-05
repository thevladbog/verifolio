package com.verifolio.privacy.application

import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.platform.VerifolioProperties
import com.verifolio.requests.RecommenderPiiErasure
import com.verifolio.requests.domain.ReferenceRequestStatus
import com.verifolio.workflows.RecurringTask
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Flow-9 debt: declined requests still hold the recommender's operational PII for a short
 * abuse-investigation window, then it is erased (privacy/DSR design §Trigger). Sweeps DECLINED
 * requests with `recommender_pii_erased_at IS NULL` whose `declined_at` is older than
 * `verifolio.privacy.decline-erasure-grace`. Per-row transactions (each `eraseForRequest` is
 * independently transactional and deletes S3 objects); idempotent; disabled in tests
 * (workflows.enabled=false) and invoked directly there.
 */
@Component
internal class RecommenderPiiErasureTask(
    private val dsl: DSLContext,
    private val recommenderPiiErasure: RecommenderPiiErasure,
    private val props: VerifolioProperties,
) : RecurringTask {

    private val log = LoggerFactory.getLogger(RecommenderPiiErasureTask::class.java)

    override val name = "recommender-pii-erasure"
    override val interval: Duration get() = props.workflows.cleanupInterval

    // No task-level transaction: each request is erased in its own transaction so one failure
    // (e.g. a transient S3 error) leaves the rest — and the next tick — able to make progress.
    override fun run() {
        val rr = REFERENCE_REQUEST
        val cutoff = OffsetDateTime.now().minus(props.privacy.declineErasureGrace)
        val candidates = dsl.select(rr.ID)
            .from(rr)
            .where(
                rr.STATUS.eq(ReferenceRequestStatus.DECLINED.name)
                    .and(rr.RECOMMENDER_PII_ERASED_AT.isNull)
                    .and(rr.DECLINED_AT.isNotNull)
                    .and(rr.DECLINED_AT.lt(cutoff)),
            )
            .orderBy(rr.DECLINED_AT.asc())
            .limit(100)
            .fetch(rr.ID)
            .filterNotNull()

        candidates.forEach { requestId ->
            try {
                recommenderPiiErasure.eraseForRequest(requestId)
            } catch (e: Exception) {
                // Log and continue; the next tick retries this request (idempotent on the marker).
                log.warn("Decline-grace erasure failed for request {}; will retry", requestId, e)
            }
        }
    }
}
