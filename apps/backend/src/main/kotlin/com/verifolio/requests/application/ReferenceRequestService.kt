package com.verifolio.requests.application

import com.verifolio.audit.AuditService
import com.verifolio.contacts.ContactLookup
import com.verifolio.identity.AuthenticatedUser
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.platform.ApiException
import com.verifolio.platform.VerifolioProperties
import com.verifolio.profiles.ProfileService
import com.verifolio.requests.api.CreateReferenceRequestRequest
import com.verifolio.requests.api.ReferenceRequestListResponse
import com.verifolio.requests.api.ReferenceRequestResponse
import com.verifolio.requests.domain.ReferenceRequestStatus
import com.verifolio.templates.TemplateLookup
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
) {

    @Transactional
    fun create(user: AuthenticatedUser, req: CreateReferenceRequestRequest): ReferenceRequestResponse {
        val requesterProfileId = profileService.requireProfileId(user.userId, user.email)

        contactLookup.findOwned(req.recommenderContactId!!, requesterProfileId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Contact not found")
        if (!templateLookup.exists(req.templateId!!)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unknown template")
        }
        if (!req.verbalConsentAttested) {
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
