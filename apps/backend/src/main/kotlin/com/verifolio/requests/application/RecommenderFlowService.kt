package com.verifolio.requests.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.InvitationAccess
import com.verifolio.identity.InvitationTokenService
import com.verifolio.identity.RecommenderGrant
import com.verifolio.identity.RecommenderSessions
import com.verifolio.jooq.tables.records.ReferenceRequestRecord
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.notifications.MailPort
import com.verifolio.platform.ApiException
import com.verifolio.profiles.ProfileService
import com.verifolio.requests.api.InvitationPreviewResponse
import com.verifolio.requests.domain.ReferenceRequestStatus
import com.verifolio.templates.TemplateLookup
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** Recommender-side flow: open, email confirmation, one-click decline. */
@Service
internal class RecommenderFlowService(
    private val dsl: DSLContext,
    private val invitationAccess: InvitationAccess,
    private val invitationTokens: InvitationTokenService,
    private val recommenderSessions: RecommenderSessions,
    private val profileService: ProfileService,
    private val templateLookup: TemplateLookup,
    private val mail: MailPort,
    private val audit: AuditService,
) {

    @Transactional
    fun open(rawToken: String): InvitationPreviewResponse {
        val info = invitationAccess.peek(rawToken) ?: throw invitationNotFound()
        val record = loadActiveRequest(info.requestId)

        var status = ReferenceRequestStatus.valueOf(record.status!!)
        if (status == ReferenceRequestStatus.SENT) {
            transition(record.id!!, status, ReferenceRequestStatus.OPENED)
            status = ReferenceRequestStatus.OPENED
            audit.record(
                actorType = "RECOMMENDER",
                actorId = null,
                action = "REFERENCE_REQUEST_OPENED",
                entityType = "REFERENCE_REQUEST",
                entityId = record.id.toString(),
            )
        }

        val template = templateLookup.snapshot(record.templateId!!)
            ?: throw invitationNotFound()
        return InvitationPreviewResponse(
            requesterName = profileService.displayName(record.requesterProfileId!!) ?: "A Verifolio user",
            purpose = record.purpose,
            templateName = template.name,
            recommenderEmailMasked = maskEmail(record.recommenderEmail!!),
            status = status.name,
        )
    }

    @Transactional
    fun requestEmailConfirmation(rawToken: String) {
        val info = invitationAccess.peek(rawToken) ?: throw invitationNotFound()
        loadActiveRequest(info.requestId)
        val rawCode = invitationAccess.issueEmailConfirmation(rawToken)
        mail.send(
            to = info.recommenderEmail,
            subject = "Your Verifolio confirmation code",
            textBody = buildString {
                appendLine("Use this code to confirm your email address:")
                appendLine()
                appendLine("Code: $rawCode")
                appendLine()
                appendLine("The code expires in 10 minutes. If you did not request it, ignore this email.")
            },
        )
    }

    @Transactional
    fun confirmEmail(rawToken: String, code: String, ipHash: String?, userAgentHash: String?): Pair<RecommenderGrant, String> {
        val info = invitationAccess.peek(rawToken) ?: throw invitationNotFound()
        val record = loadActiveRequest(info.requestId)
        val grant = invitationAccess.confirmEmail(rawToken, code, ipHash, userAgentHash)
        return grant to record.status!!
    }

    @Transactional
    fun declineByToken(rawToken: String, reason: String) {
        val info = invitationAccess.identify(rawToken) ?: throw invitationNotFound()
        val record = loadRequest(info.requestId) ?: throw invitationNotFound()
        val status = ReferenceRequestStatus.valueOf(record.status!!)
        if (!status.canTransitionTo(ReferenceRequestStatus.DECLINED)) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "INVALID_REQUEST_STATE",
                "Request can no longer be declined",
            )
        }

        transition(record.id!!, status, ReferenceRequestStatus.DECLINED)
        invitationTokens.revokeForRequest(record.id!!)
        recommenderSessions.revokeForRequest(record.id!!)
        audit.record(
            actorType = "RECOMMENDER",
            actorId = null,
            action = "REQUEST_DECLINED",
            entityType = "REFERENCE_REQUEST",
            entityId = record.id.toString(),
            metadata = mapOf("reason" to reason, "previousStatus" to status.name),
        )
    }

    // ---- shared helpers (also used by the session-scoped flow) ----

    internal fun loadRequest(requestId: UUID): ReferenceRequestRecord? =
        dsl.selectFrom(REFERENCE_REQUEST).where(REFERENCE_REQUEST.ID.eq(requestId)).fetchOne()

    internal fun loadActiveRequest(requestId: UUID): ReferenceRequestRecord {
        val record = loadRequest(requestId) ?: throw invitationNotFound()
        val status = ReferenceRequestStatus.valueOf(record.status!!)
        if (status.terminal) throw invitationNotFound()
        return record
    }

    internal fun transition(requestId: UUID, from: ReferenceRequestStatus, to: ReferenceRequestStatus) {
        check(from.canTransitionTo(to)) { "Illegal transition $from -> $to" }
        dsl.update(REFERENCE_REQUEST)
            .set(REFERENCE_REQUEST.STATUS, to.name)
            .set(REFERENCE_REQUEST.UPDATED_AT, OffsetDateTime.now())
            .where(REFERENCE_REQUEST.ID.eq(requestId))
            .execute()
    }

    private fun invitationNotFound() =
        ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Invitation not found")

    companion object {
        /** j***@corp.example.com — enough for the recommender to recognise their address. */
        fun maskEmail(email: String): String {
            val at = email.indexOf('@')
            if (at <= 1) return "***${email.substring(at.coerceAtLeast(0))}"
            return "${email.first()}***${email.substring(at)}"
        }
    }
}
