package com.verifolio.requests.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.InvitationAccess
import com.verifolio.identity.InvitationTokenService
import com.verifolio.identity.RecommenderActor
import com.verifolio.identity.RecommenderGrant
import com.verifolio.identity.RecommenderSessions
import com.verifolio.jooq.tables.records.ReferenceRequestRecord
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.jooq.tables.references.REFERENCE_RESPONSE
import com.verifolio.notifications.MailPort
import com.verifolio.platform.ApiException
import com.verifolio.platform.VerifolioProperties
import com.verifolio.profiles.ProfileService
import com.verifolio.requests.api.ConsentDecisionRequest
import com.verifolio.requests.api.ConsentTextRef
import com.verifolio.requests.api.ConsentTextsDto
import com.verifolio.requests.api.DraftDto
import com.verifolio.requests.api.DraftRequest
import com.verifolio.requests.api.InvitationPreviewResponse
import com.verifolio.requests.api.RecommenderRequestContext
import com.verifolio.requests.api.SubmitResponseRequest
import com.verifolio.requests.domain.ReferenceRequestStatus
import com.verifolio.templates.TemplateLookup
import com.verifolio.platform.SlidingWindowRateLimiter
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
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
    private val props: VerifolioProperties,
    private val objectMapper: ObjectMapper,
    @Qualifier("emailConfirmationLimiter") private val codeLimiter: SlidingWindowRateLimiter,
) {

    @Transactional
    fun open(rawToken: String): InvitationPreviewResponse {
        val info = invitationAccess.peek(rawToken) ?: throw invitationNotFound()
        val record = loadActiveRequest(info.requestId)

        var status = ReferenceRequestStatus.valueOf(record.status!!)
        if (status == ReferenceRequestStatus.SENT) {
            // Tolerant CAS: a concurrent first open wins the flip; either way the preview
            // is served, and the OPENED audit event is emitted exactly once.
            val flipped = tryTransition(record.id!!, status, ReferenceRequestStatus.OPENED)
            status = ReferenceRequestStatus.OPENED
            if (flipped) {
                audit.record(
                    actorType = "RECOMMENDER",
                    actorId = null,
                    action = "REFERENCE_REQUEST_OPENED",
                    entityType = "REFERENCE_REQUEST",
                    entityId = record.id.toString(),
                )
            }
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

        val limiterKey = info.requestId.toString()
        if (!codeLimiter.tryAcquire(limiterKey)) {
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Too many confirmation codes were requested for this invitation",
            )
        }

        try {
            val rawCode = invitationAccess.issueEmailConfirmation(rawToken)
            val ttlMinutes = props.auth.emailConfirmationTtl.toMinutes()
            mail.send(
                to = info.recommenderEmail,
                subject = "Your Verifolio confirmation code",
                textBody = buildString {
                    appendLine("Use this code to confirm your email address:")
                    appendLine()
                    appendLine("Code: $rawCode")
                    appendLine()
                    appendLine("The code expires in $ttlMinutes minutes. If you did not request it, ignore this email.")
                },
            )
        } catch (e: Exception) {
            // Transaction rolls back the stored code — refund the limiter slot so a mail
            // outage doesn't exhaust the invitation's window (same pattern as send).
            codeLimiter.release(limiterKey)
            throw e
        }
    }

    @Transactional
    fun confirmEmail(rawToken: String, code: String, rawIp: String?, rawUserAgent: String?): Pair<RecommenderGrant, String> {
        val info = invitationAccess.peek(rawToken) ?: throw invitationNotFound()
        val record = loadActiveRequest(info.requestId)
        val grant = invitationAccess.confirmEmail(rawToken, code, rawIp, rawUserAgent)
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

    // ---- session-scoped flow ----

    @Transactional(readOnly = true)
    fun context(actor: RecommenderActor): RecommenderRequestContext {
        val record = loadActiveRequest(actor.requestId)
        val template = templateLookup.snapshot(record.templateId!!) ?: throw invitationNotFound()
        val rr = REFERENCE_RESPONSE
        val draft = dsl.selectFrom(rr)
            .where(rr.REQUEST_ID.eq(actor.requestId).and(rr.SUBMITTED_AT.isNull))
            .fetchOne()
        return RecommenderRequestContext(
            status = record.status!!,
            requesterName = profileService.displayName(record.requesterProfileId!!) ?: "A Verifolio user",
            purpose = record.purpose,
            templateName = template.name,
            questionSchema = parseJson(template.questionSchemaJson),
            consents = ConsentTextsDto(
                processing = ConsentTextRef(props.consents.processing.textId, props.consents.processing.version),
                crossBorderTransfer = ConsentTextRef(
                    props.consents.crossBorderTransfer.textId,
                    props.consents.crossBorderTransfer.version,
                ),
            ),
            draft = draft?.let {
                DraftDto(
                    answersJson = parseJson(it.answersJson!!.data()),
                    approvedLetterText = it.approvedLetterText,
                    updatedAt = it.updatedAt!!.toString(),
                )
            },
        )
    }

    @Transactional
    fun consent(actor: RecommenderActor, decision: ConsentDecisionRequest): ReferenceRequestStatus {
        val record = loadActiveRequest(actor.requestId)
        val status = ReferenceRequestStatus.valueOf(record.status!!)
        if (status != ReferenceRequestStatus.OPENED) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "INVALID_REQUEST_STATE",
                "The consent decision is only accepted at the consent gate (OPENED)",
            )
        }
        val now = OffsetDateTime.now()

        return if (decision.accepted == true) {
            val processingConsentId = insertRecommenderConsent(
                record, "RECOMMENDER_PROCESSING_CONSENT", props.consents.processing.versionedId,
                granted = true, now = now,
            )
            auditConsent("CONSENT_GRANTED", processingConsentId, record, "RECOMMENDER_PROCESSING_CONSENT", props.consents.processing.versionedId)
            // crossBorderAccepted == null means the consent was not presented (same-cell
            // case); an explicit true/false is always recorded for the audit trail.
            if (decision.crossBorderAccepted != null) {
                val granted = decision.crossBorderAccepted
                val crossBorderConsentId = insertRecommenderConsent(
                    record, "CROSS_BORDER_TRANSFER_CONSENT", props.consents.crossBorderTransfer.versionedId,
                    granted = granted, now = now,
                )
                auditConsent(
                    if (granted) "CONSENT_GRANTED" else "CONSENT_DECLINED",
                    crossBorderConsentId, record, "CROSS_BORDER_TRANSFER_CONSENT",
                    props.consents.crossBorderTransfer.versionedId,
                )
            }
            transition(record.id!!, status, ReferenceRequestStatus.IN_PROGRESS)
            ReferenceRequestStatus.IN_PROGRESS
        } else {
            val declinedConsentId = insertRecommenderConsent(
                record, "RECOMMENDER_PROCESSING_CONSENT", props.consents.processing.versionedId,
                granted = false, now = now,
            )
            auditConsent("CONSENT_DECLINED", declinedConsentId, record, "RECOMMENDER_PROCESSING_CONSENT", props.consents.processing.versionedId)
            transition(record.id!!, status, ReferenceRequestStatus.DECLINED)
            invitationTokens.revokeForRequest(record.id!!)
            recommenderSessions.revokeForRequest(record.id!!)
            audit.record(
                actorType = "RECOMMENDER",
                actorId = null,
                action = "REQUEST_DECLINED",
                entityType = "REFERENCE_REQUEST",
                entityId = record.id.toString(),
                metadata = mapOf("reason" to "consent_declined", "previousStatus" to status.name),
            )
            ReferenceRequestStatus.DECLINED
        }
    }

    @Transactional
    fun saveDraft(actor: RecommenderActor, draft: DraftRequest): DraftDto {
        val record = loadActiveRequest(actor.requestId)
        requireStatus(record, ReferenceRequestStatus.IN_PROGRESS)

        val rr = REFERENCE_RESPONSE
        val answers = JSONB.valueOf(objectMapper.writeValueAsString(draft.answersJson))
        val existing = dsl.selectFrom(rr)
            .where(rr.REQUEST_ID.eq(actor.requestId).and(rr.SUBMITTED_AT.isNull))
            .forUpdate()
            .fetchOne()

        val row = if (existing == null) {
            val inserted = dsl.insertInto(rr)
                .set(rr.REQUEST_ID, actor.requestId)
                .set(rr.RECOMMENDER_EMAIL, actor.email)
                .set(rr.ANSWERS_JSON, answers)
                .set(rr.APPROVED_LETTER_TEXT, draft.approvedLetterText)
                .returning()
                .fetchOne()!!
            audit.record(
                actorType = "RECOMMENDER",
                actorId = null,
                action = "REFERENCE_RESPONSE_STARTED",
                entityType = "REFERENCE_RESPONSE",
                entityId = inserted.id.toString(),
                metadata = mapOf("requestId" to actor.requestId.toString()),
            )
            inserted
        } else {
            dsl.update(rr)
                .set(rr.ANSWERS_JSON, answers)
                .set(rr.APPROVED_LETTER_TEXT, draft.approvedLetterText)
                .set(rr.UPDATED_AT, OffsetDateTime.now())
                .where(rr.ID.eq(existing.id))
                .returning()
                .fetchOne()!!
        }

        return DraftDto(
            answersJson = parseJson(row.answersJson!!.data()),
            approvedLetterText = row.approvedLetterText,
            updatedAt = row.updatedAt!!.toString(),
        )
    }

    @Transactional
    fun submit(actor: RecommenderActor, req: SubmitResponseRequest): ReferenceRequestStatus {
        val record = loadActiveRequest(actor.requestId)
        requireStatus(record, ReferenceRequestStatus.IN_PROGRESS)

        val cr = CONSENT_RECORD
        val consentGranted = dsl.fetchExists(
            dsl.selectFrom(cr).where(
                cr.REFERENCE_REQUEST_ID.eq(actor.requestId)
                    .and(cr.CONSENT_TYPE.eq("RECOMMENDER_PROCESSING_CONSENT"))
                    .and(cr.STATUS.eq("GRANTED")),
            ),
        )
        if (!consentGranted) {
            throw ApiException(HttpStatus.CONFLICT, "CONSENT_REQUIRED", "Processing consent record is missing")
        }
        if (req.recipientConfirmed != true || req.relationshipConfirmed != true) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "CONFIRMATION_REQUIRED",
                "Recipient and relationship confirmations are required before submission",
            )
        }

        val now = OffsetDateTime.now()
        val rr = REFERENCE_RESPONSE
        val existing = dsl.selectFrom(rr)
            .where(rr.REQUEST_ID.eq(actor.requestId).and(rr.SUBMITTED_AT.isNull))
            .forUpdate()
            .fetchOne()
        val answers = req.answersJson?.let { JSONB.valueOf(objectMapper.writeValueAsString(it)) }

        val responseId = if (existing == null) {
            dsl.insertInto(rr)
                .set(rr.REQUEST_ID, actor.requestId)
                .set(rr.RECOMMENDER_EMAIL, actor.email)
                .set(rr.ANSWERS_JSON, answers ?: JSONB.valueOf("{}"))
                .set(rr.APPROVED_LETTER_TEXT, req.approvedLetterText)
                .set(rr.CONFIRMATION_TEXT, req.confirmationText)
                .set(rr.RECIPIENT_CONFIRMED, true)
                .set(rr.RELATIONSHIP_CONFIRMED, true)
                .set(rr.SUBMITTED_AT, now)
                .returning(rr.ID)
                .fetchOne()!!.id!!
        } else {
            var update = dsl.update(rr)
                .set(rr.APPROVED_LETTER_TEXT, req.approvedLetterText)
                .set(rr.CONFIRMATION_TEXT, req.confirmationText)
                .set(rr.RECIPIENT_CONFIRMED, true)
                .set(rr.RELATIONSHIP_CONFIRMED, true)
                .set(rr.SUBMITTED_AT, now)
                .set(rr.UPDATED_AT, now)
            if (answers != null) update = update.set(rr.ANSWERS_JSON, answers)
            update.where(rr.ID.eq(existing.id)).execute()
            existing.id!!
        }

        // IN_PROGRESS -> SUBMITTED -> NEEDS_REVIEW: the second hop is the automatic
        // system transition into the recipient review queue (WORKFLOWS.md).
        transition(record.id!!, ReferenceRequestStatus.IN_PROGRESS, ReferenceRequestStatus.SUBMITTED)
        transition(record.id!!, ReferenceRequestStatus.SUBMITTED, ReferenceRequestStatus.NEEDS_REVIEW)

        audit.record(
            actorType = "RECOMMENDER",
            actorId = null,
            action = "REFERENCE_RESPONSE_SUBMITTED",
            entityType = "REFERENCE_RESPONSE",
            entityId = responseId.toString(),
            metadata = mapOf("requestId" to actor.requestId.toString()),
        )
        audit.record(
            actorType = "RECOMMENDER", actorId = null,
            action = "RECIPIENT_CONFIRMED_BY_RECOMMENDER",
            entityType = "REFERENCE_RESPONSE", entityId = responseId.toString(),
        )
        audit.record(
            actorType = "RECOMMENDER", actorId = null,
            action = "RELATIONSHIP_CONFIRMED_BY_RECOMMENDER",
            entityType = "REFERENCE_RESPONSE", entityId = responseId.toString(),
        )

        recommenderSessions.revokeForRequest(actor.requestId)
        return ReferenceRequestStatus.NEEDS_REVIEW
    }

    private fun insertRecommenderConsent(
        record: ReferenceRequestRecord,
        consentType: String,
        policyTextVersion: String,
        granted: Boolean,
        now: OffsetDateTime,
    ): UUID {
        val cr = CONSENT_RECORD
        var step = dsl.insertInto(cr)
            .set(cr.SUBJECT_TYPE, "RECOMMENDER")
            .set(cr.RECOMMENDER_CONTACT_ID, record.recommenderContactId)
            .set(cr.REFERENCE_REQUEST_ID, record.id)
            .set(cr.CONSENT_TYPE, consentType)
            .set(cr.POLICY_TEXT_VERSION, policyTextVersion)
            .set(cr.REGION, props.region)
            .set(cr.STATUS, if (granted) "GRANTED" else "DECLINED")
        step = if (granted) step.set(cr.GRANTED_AT, now) else step.set(cr.DECLINED_AT, now)
        return step.returning(cr.ID).fetchOne()!!.id!!
    }

    private fun auditConsent(
        action: String,
        consentId: UUID,
        record: ReferenceRequestRecord,
        consentType: String,
        policyTextVersion: String,
    ) {
        audit.record(
            actorType = "RECOMMENDER",
            actorId = null,
            action = action,
            entityType = "CONSENT_RECORD",
            entityId = consentId.toString(),
            metadata = mapOf(
                "consentType" to consentType,
                "policyTextVersion" to policyTextVersion,
                "region" to props.region,
                "referenceRequestId" to record.id.toString(),
            ),
        )
    }

    private fun requireStatus(record: ReferenceRequestRecord, expected: ReferenceRequestStatus) {
        if (ReferenceRequestStatus.valueOf(record.status!!) != expected) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "INVALID_REQUEST_STATE",
                "Request must be in the $expected status for this action",
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(json: String): Map<String, Any?> =
        objectMapper.readValue(json, Map::class.java) as Map<String, Any?>

    // ---- shared helpers (also used by the session-scoped flow) ----

    internal fun loadRequest(requestId: UUID): ReferenceRequestRecord? =
        dsl.selectFrom(REFERENCE_REQUEST).where(REFERENCE_REQUEST.ID.eq(requestId)).fetchOne()

    internal fun loadActiveRequest(requestId: UUID): ReferenceRequestRecord {
        val record = loadRequest(requestId) ?: throw invitationNotFound()
        val status = ReferenceRequestStatus.valueOf(record.status!!)
        if (status.terminal) throw invitationNotFound()
        return record
    }

    /**
     * Compare-and-set transition: the WHERE clause pins the expected current status so a
     * stale caller (e.g. the requester cancelled concurrently) cannot overwrite a terminal
     * state. Throws 409 when another transaction moved the status first.
     */
    internal fun transition(requestId: UUID, from: ReferenceRequestStatus, to: ReferenceRequestStatus) {
        if (!tryTransition(requestId, from, to)) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "INVALID_REQUEST_STATE",
                "The request status changed concurrently; reload and retry",
            )
        }
    }

    private fun tryTransition(requestId: UUID, from: ReferenceRequestStatus, to: ReferenceRequestStatus): Boolean {
        check(from.canTransitionTo(to)) { "Illegal transition $from -> $to" }
        return dsl.update(REFERENCE_REQUEST)
            .set(REFERENCE_REQUEST.STATUS, to.name)
            .set(REFERENCE_REQUEST.UPDATED_AT, OffsetDateTime.now())
            .where(REFERENCE_REQUEST.ID.eq(requestId).and(REFERENCE_REQUEST.STATUS.eq(from.name)))
            .execute() == 1
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
