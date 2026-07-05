package com.verifolio.requests.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.InvitationTokenService
import com.verifolio.identity.RecommenderSessions
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.notifications.MailPort
import com.verifolio.platform.VerifolioProperties
import com.verifolio.requests.domain.ReferenceRequestStatus
import com.verifolio.workflows.RecurringTask
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

private val ACTIVE_STATUSES = listOf(
    ReferenceRequestStatus.SENT.name,
    ReferenceRequestStatus.OPENED.name,
    ReferenceRequestStatus.IN_PROGRESS.name,
    ReferenceRequestStatus.CORRECTION_REQUESTED.name,
)

/**
 * Reminder Policy + auto-expiration (docs/WORKFLOWS.md) on the ADR-0005 scheduler
 * fallback. Each due row is processed in its own transaction so one failure does not
 * block the batch; a failed row is retried on the next tick.
 */
@Component
internal class ReferenceRequestLifecycleTask(
    private val dsl: DSLContext,
    private val actions: LifecycleActions,
    private val props: VerifolioProperties,
) : RecurringTask {

    private val log = LoggerFactory.getLogger(ReferenceRequestLifecycleTask::class.java)

    override val name = "reference-request-lifecycle"
    override val interval: Duration get() = props.workflows.tickInterval

    override fun run() {
        val rr = REFERENCE_REQUEST
        val now = OffsetDateTime.now()
        val offsets = props.workflows.reminderOffsets

        // Reminders: candidates by status/state; the per-row offset check happens here
        // because the due offset depends on the row's own reminders_sent.
        val reminderCandidates = dsl.selectFrom(rr)
            .where(
                rr.STATUS.`in`(ACTIVE_STATUSES)
                    .and(rr.SENT_AT.isNotNull)
                    .and(rr.REMINDERS_STOPPED_AT.isNull)
                    .and(rr.REMINDERS_SENT.lt(offsets.size)),
            )
            .fetch()
        reminderCandidates
            .filter { row -> row.sentAt!!.plus(offsets[row.remindersSent!!]) <= now }
            .forEach { row ->
                runCatching { actions.sendReminder(row.id!!) }
                    .onFailure { log.error("Reminder failed for request {}", row.id, it) }
            }

        // Expiration.
        val expired = dsl.select(rr.ID).from(rr)
            .where(rr.STATUS.`in`(ACTIVE_STATUSES).and(rr.EXPIRES_AT.le(now)))
            .fetch(rr.ID)
        expired.filterNotNull().forEach { id ->
            runCatching { actions.expire(id) }
                .onFailure { log.error("Expiration failed for request {}", id, it) }
        }
    }
}

/** Row-level actions in independent transactions. */
@Service
internal class LifecycleActions(
    private val dsl: DSLContext,
    private val invitationTokens: InvitationTokenService,
    private val recommenderSessions: RecommenderSessions,
    private val mail: MailPort,
    private val audit: AuditService,
    private val props: VerifolioProperties,
) {

    @Transactional
    fun sendReminder(requestId: UUID) {
        val rr = REFERENCE_REQUEST
        val row = dsl.selectFrom(rr).where(rr.ID.eq(requestId)).forUpdate().fetchOne() ?: return
        val offsets = props.workflows.reminderOffsets
        val reminderIndex = row.remindersSent!!
        // Re-check under the lock: another tick may have advanced the state.
        if (row.status !in ACTIVE_STATUSES || row.remindersStoppedAt != null ||
            reminderIndex >= offsets.size || row.sentAt!!.plus(offsets[reminderIndex]) > OffsetDateTime.now()
        ) {
            return
        }

        // Raw tokens are never stored — reminders must re-mint the link.
        invitationTokens.revokeForRequest(requestId)
        val remaining = Duration.between(OffsetDateTime.now(), row.expiresAt)
        val ttl = if (remaining > Duration.ofDays(1)) remaining else Duration.ofDays(1)
        val rawToken = invitationTokens.mint(requestId, row.recommenderEmail!!, ttl)

        val isFinalWarning = reminderIndex == offsets.size - 1
        val base = props.auth.frontendBaseUrl
        mail.send(
            to = row.recommenderEmail!!,
            subject = if (isFinalWarning) "Reminder: reference request expires soon" else "Reminder: reference request",
            textBody = buildString {
                appendLine("Hello ${row.recommenderName},")
                appendLine()
                appendLine("This is a reminder about the pending reference request.")
                if (isFinalWarning) {
                    appendLine("This request expires soon; any saved draft will be deleted with it.")
                }
                appendLine()
                appendLine("Open the request: $base/invitations/$rawToken")
                appendLine()
                appendLine("Stop these reminders: $base/invitations/$rawToken/stop-reminders")
                appendLine("If you prefer not to respond, decline here: $base/invitations/$rawToken/decline")
                appendLine("Report abuse: $base/invitations/$rawToken/report-abuse")
            },
        )

        // Incremented only after a successful send — a mail failure rolls back and retries.
        dsl.update(rr)
            .set(rr.REMINDERS_SENT, reminderIndex + 1)
            .set(rr.UPDATED_AT, OffsetDateTime.now())
            .where(rr.ID.eq(requestId))
            .execute()

        audit.record(
            actorType = "SYSTEM",
            actorId = null,
            action = "REFERENCE_REQUEST_REMINDER_SENT",
            entityType = "REFERENCE_REQUEST",
            entityId = requestId.toString(),
            metadata = mapOf("reminderNumber" to (reminderIndex + 1).toString(), "of" to offsets.size.toString()),
        )
    }

    @Transactional
    fun expire(requestId: UUID) {
        val rr = REFERENCE_REQUEST
        val row = dsl.selectFrom(rr).where(rr.ID.eq(requestId)).forUpdate().fetchOne() ?: return
        val status = ReferenceRequestStatus.valueOf(row.status!!)
        if (!status.canTransitionTo(ReferenceRequestStatus.EXPIRED)) return

        val updated = dsl.update(rr)
            .set(rr.STATUS, ReferenceRequestStatus.EXPIRED.name)
            .set(rr.UPDATED_AT, OffsetDateTime.now())
            .where(rr.ID.eq(requestId).and(rr.STATUS.eq(status.name)))
            .execute()
        if (updated == 0) return

        invitationTokens.revokeForRequest(requestId)
        recommenderSessions.revokeForRequest(requestId)

        audit.record(
            actorType = "SYSTEM",
            actorId = null,
            action = "REFERENCE_REQUEST_EXPIRED",
            entityType = "REFERENCE_REQUEST",
            entityId = requestId.toString(),
            metadata = mapOf("previousStatus" to status.name),
        )
    }
}
