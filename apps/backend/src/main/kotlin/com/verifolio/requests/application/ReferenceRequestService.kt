package com.verifolio.requests.application

import com.verifolio.audit.AuditService
import com.verifolio.contacts.ContactLookup
import com.verifolio.identity.AuthenticatedUser
import com.verifolio.identity.InvitationTokenService
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.notifications.MailPort
import com.verifolio.platform.ApiException
import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.VerifolioProperties
import com.verifolio.profiles.ProfileService
import com.verifolio.requests.api.CreateReferenceRequestRequest
import com.verifolio.requests.api.ReferenceRequestListResponse
import com.verifolio.requests.api.ReferenceRequestResponse
import com.verifolio.requests.domain.ReferenceRequestStatus
import com.verifolio.templates.TemplateLookup
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

private const val PAGE_SIZE = 50

internal const val REQUESTER_ATTESTATION = "REQUESTER_VERBAL_CONSENT_ATTESTATION"

@Service
internal class ReferenceRequestService(
    private val dsl: DSLContext,
    private val profileService: ProfileService,
    private val contactLookup: ContactLookup,
    private val templateLookup: TemplateLookup,
    private val audit: AuditService,
    private val props: VerifolioProperties,
    private val invitationTokens: InvitationTokenService,
    private val mail: MailPort,
    @Qualifier("referenceRequestSendLimiter") private val sendLimiter: SlidingWindowRateLimiter,
) {

    @Transactional
    fun create(user: AuthenticatedUser, req: CreateReferenceRequestRequest): ReferenceRequestResponse {
        val requesterProfileId = profileService.requireProfileId(user.userId, user.email)

        val contact = contactLookup.findOwned(req.recommenderContactId!!, requesterProfileId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Contact not found")
        if (!templateLookup.exists(req.templateId!!)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unknown template")
        }
        if (req.verbalConsentAttested != true) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "CONSENT_REQUIRED",
                "The verbal-consent attestation checkbox must be confirmed before creating a request",
            )
        }

        val rr = REFERENCE_REQUEST
        val record = dsl.insertInto(rr)
            .set(rr.REQUESTER_PROFILE_ID, requesterProfileId)
            .set(rr.RECOMMENDER_CONTACT_ID, req.recommenderContactId)
            // Snapshot: the attestation covers this recipient; later contact edits must not
            // redirect an already-attested invitation.
            .set(rr.RECOMMENDER_NAME, contact.name)
            .set(rr.RECOMMENDER_EMAIL, contact.email)
            .set(rr.TEMPLATE_ID, req.templateId)
            .set(rr.PURPOSE, req.purpose)
            .set(rr.STATUS, ReferenceRequestStatus.CREATED.name)
            .set(rr.EXPIRES_AT, OffsetDateTime.now().plus(props.requests.expiry))
            .returning()
            .fetchOne()!!

        val cr = CONSENT_RECORD
        val consentId = dsl.insertInto(cr)
            .set(cr.SUBJECT_TYPE, "REQUESTER")
            .set(cr.USER_ID, user.userId)
            .set(cr.REFERENCE_REQUEST_ID, record.id)
            .set(cr.CONSENT_TYPE, REQUESTER_ATTESTATION)
            .set(cr.POLICY_TEXT_VERSION, props.consents.requesterAttestation.versionedId)
            .set(cr.REGION, props.region)
            .set(cr.STATUS, "GRANTED")
            .set(cr.GRANTED_AT, OffsetDateTime.now())
            .returning(cr.ID)
            .fetchOne()!!.id!!

        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "REFERENCE_REQUEST_CREATED",
            entityType = "REFERENCE_REQUEST",
            entityId = record.id.toString(),
            metadata = mapOf(
                "templateId" to req.templateId.toString(),
                "recommenderContactId" to req.recommenderContactId.toString(),
            ),
        )
        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "CONSENT_GRANTED",
            entityType = "CONSENT_RECORD",
            entityId = consentId.toString(),
            metadata = mapOf(
                "consentType" to REQUESTER_ATTESTATION,
                "policyTextVersion" to props.consents.requesterAttestation.versionedId,
                "region" to props.region,
                "referenceRequestId" to record.id.toString(),
            ),
        )

        return record.toResponse()
    }

    @Transactional
    fun send(user: AuthenticatedUser, id: UUID): ReferenceRequestResponse {
        val requesterProfileId = profileService.requireProfileId(user.userId, user.email)
        val rr = REFERENCE_REQUEST
        val record = dsl.selectFrom(rr)
            .where(rr.ID.eq(id).and(rr.REQUESTER_PROFILE_ID.eq(requesterProfileId)))
            .forUpdate()
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Reference request not found")

        val status = ReferenceRequestStatus.valueOf(record.status!!)
        if (status != ReferenceRequestStatus.CREATED) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "INVALID_REQUEST_STATE",
                "Request can be sent only from the CREATED status",
            )
        }
        val now = OffsetDateTime.now()
        if (!record.expiresAt!!.isAfter(now)) {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_REQUEST_STATE", "Request has expired")
        }

        val cr = CONSENT_RECORD
        val attestationExists = dsl.fetchExists(
            dsl.selectFrom(cr).where(
                cr.REFERENCE_REQUEST_ID.eq(id)
                    .and(cr.CONSENT_TYPE.eq(REQUESTER_ATTESTATION))
                    .and(cr.STATUS.eq("GRANTED")),
            ),
        )
        if (!attestationExists) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "CONSENT_REQUIRED",
                "Verbal-consent attestation record is missing for this request",
            )
        }

        // The snapshot taken at creation is authoritative: the verbal-consent attestation
        // covers exactly this recipient, so contact edits after creation have no effect here.
        val recommenderEmail = record.recommenderEmail!!
        val recommenderName = record.recommenderName!!

        val limiterKey = recommenderEmail.lowercase()
        if (!sendLimiter.tryAcquire(limiterKey)) {
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Too many reference requests were sent to this recommender recently",
            )
        }

        try {
            val rawToken = invitationTokens.mint(
                requestId = id,
                recommenderEmail = recommenderEmail,
                ttl = Duration.between(now, record.expiresAt),
            )

            val base = props.auth.frontendBaseUrl
            mail.send(
                to = recommenderEmail,
                subject = "Reference request from ${user.email}",
                textBody = buildString {
                    appendLine("Hello $recommenderName,")
                    appendLine()
                    appendLine("${user.email} asks you for a professional reference via Verifolio.")
                    record.purpose?.let {
                        appendLine()
                        appendLine("Context: $it")
                    }
                    appendLine()
                    appendLine("Open the request: $base/invitations/$rawToken")
                    appendLine()
                    appendLine("If you prefer not to respond, decline here: $base/invitations/$rawToken/decline")
                    appendLine("Report abuse: $base/invitations/$rawToken/report-abuse")
                },
            )
        } catch (e: Exception) {
            // The transaction rolls back (no token, status stays CREATED) — refund the
            // limiter slot so transient mail outages don't exhaust the recommender's window.
            sendLimiter.release(limiterKey)
            throw e
        }

        val updated = dsl.update(rr)
            .set(rr.STATUS, ReferenceRequestStatus.SENT.name)
            .set(rr.UPDATED_AT, now)
            .where(rr.ID.eq(id))
            .returning()
            .fetchOne()!!

        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "REFERENCE_REQUEST_SENT",
            entityType = "REFERENCE_REQUEST",
            entityId = id.toString(),
        )

        return updated.toResponse()
    }

    @Transactional
    fun cancel(user: AuthenticatedUser, id: UUID): ReferenceRequestResponse {
        val requesterProfileId = profileService.requireProfileId(user.userId, user.email)
        val rr = REFERENCE_REQUEST
        val record = dsl.selectFrom(rr)
            .where(rr.ID.eq(id).and(rr.REQUESTER_PROFILE_ID.eq(requesterProfileId)))
            .forUpdate()
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Reference request not found")

        val status = ReferenceRequestStatus.valueOf(record.status!!)
        if (!status.canTransitionTo(ReferenceRequestStatus.CANCELLED)) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "INVALID_REQUEST_STATE",
                "Request in a terminal status cannot be cancelled",
            )
        }

        val updated = dsl.update(rr)
            .set(rr.STATUS, ReferenceRequestStatus.CANCELLED.name)
            .set(rr.UPDATED_AT, OffsetDateTime.now())
            .where(rr.ID.eq(id))
            .returning()
            .fetchOne()!!

        invitationTokens.revokeForRequest(id)

        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "REFERENCE_REQUEST_CANCELLED",
            entityType = "REFERENCE_REQUEST",
            entityId = id.toString(),
            metadata = mapOf("previousStatus" to status.name),
        )

        return updated.toResponse()
    }

    @Transactional(readOnly = true)
    fun get(user: AuthenticatedUser, id: UUID): ReferenceRequestResponse {
        val requesterProfileId = profileService.requireProfileId(user.userId, user.email)
        val rr = REFERENCE_REQUEST
        return dsl.selectFrom(rr)
            .where(rr.ID.eq(id).and(rr.REQUESTER_PROFILE_ID.eq(requesterProfileId)))
            .fetchOne()
            ?.toResponse()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Reference request not found")
    }

    @Transactional(readOnly = true)
    fun list(user: AuthenticatedUser, cursor: String?, status: String?): ReferenceRequestListResponse {
        val requesterProfileId = profileService.requireProfileId(user.userId, user.email)
        val rr = REFERENCE_REQUEST

        val statusCondition = if (status != null) {
            val parsed = runCatching { ReferenceRequestStatus.valueOf(status) }.getOrElse {
                throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unknown status filter")
            }
            rr.STATUS.eq(parsed.name)
        } else {
            DSL.noCondition()
        }

        val cursorCondition = if (cursor != null) {
            val (cursorTs, cursorId) = decodeCursor(cursor)
            DSL.row(rr.CREATED_AT, rr.ID).gt(cursorTs, cursorId)
        } else {
            DSL.noCondition()
        }

        val rows = dsl.selectFrom(rr)
            .where(rr.REQUESTER_PROFILE_ID.eq(requesterProfileId).and(statusCondition).and(cursorCondition))
            .orderBy(rr.CREATED_AT.asc(), rr.ID.asc())
            .limit(PAGE_SIZE + 1)
            .fetch()

        val hasMore = rows.size > PAGE_SIZE
        val items = if (hasMore) rows.take(PAGE_SIZE) else rows

        val nextCursor = if (hasMore) {
            val last = items.last()
            encodeCursor(last.createdAt!!, last.id!!)
        } else {
            null
        }

        return ReferenceRequestListResponse(
            items = items.map { it.toResponse() },
            nextCursor = nextCursor,
        )
    }

    // ---- helpers ----

    private fun encodeCursor(createdAt: OffsetDateTime, id: UUID): String {
        val raw = "${createdAt}|${id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    private fun decodeCursor(cursor: String): Pair<OffsetDateTime, UUID> {
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val delimIndex = decoded.lastIndexOf('|')
            require(delimIndex > 0)
            val ts = OffsetDateTime.parse(decoded.substring(0, delimIndex))
            val id = UUID.fromString(decoded.substring(delimIndex + 1))
            ts to id
        }.getOrElse {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid cursor")
        }
    }

    private fun com.verifolio.jooq.tables.records.ReferenceRequestRecord.toResponse() = ReferenceRequestResponse(
        id = id!!.toString(),
        recommenderContactId = recommenderContactId!!.toString(),
        templateId = templateId!!.toString(),
        purpose = purpose,
        status = status!!,
        expiresAt = expiresAt!!.toString(),
        createdAt = createdAt!!.toString(),
        updatedAt = updatedAt?.toString(),
    )
}
