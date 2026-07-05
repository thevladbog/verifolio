package com.verifolio.privacy.application

import com.verifolio.audit.AuditService
import com.verifolio.documents.DocumentRetraction
import com.verifolio.documents.DocumentTombstone
import com.verifolio.identity.AuthenticatedUser
import com.verifolio.jooq.tables.records.DataSubjectRequestRecord
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.notifications.MailPort
import com.verifolio.platform.ApiException
import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.VerifolioProperties
import com.verifolio.privacy.api.CreateDataSubjectRequestRequest
import com.verifolio.privacy.api.DataSubjectRequestListResponse
import com.verifolio.privacy.api.DataSubjectRequestResponse
import com.verifolio.privacy.api.RecommenderDsrVerifyResponse
import com.verifolio.privacy.domain.DsrStatus
import com.verifolio.privacy.domain.DsrType
import com.verifolio.requests.ConsentWithdrawal
import com.verifolio.requests.RecommenderPiiErasure
import com.verifolio.requests.RecommenderRequestRef
import com.verifolio.requests.RequestPublicView
import com.verifolio.verification.VerificationSignals
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

private const val PAGE_SIZE = 50

/**
 * DSR intake (both channels), keyset listing, and the hybrid execution engine (privacy/DSR
 * design §Execution model). CONSENT_WITHDRAWAL auto-executes at verification; the other types
 * stay RECEIVED for manual/admin execution. `execute(id)` is the ops entry point — no HTTP
 * endpoint exposes it in this iteration.
 */
@Service
internal class DataSubjectRequestService(
    private val dsl: DSLContext,
    private val audit: AuditService,
    private val props: VerifolioProperties,
    private val consentWithdrawal: ConsentWithdrawal,
    private val executor: ConsentWithdrawalExecutor,
    private val recommenderPiiErasure: RecommenderPiiErasure,
    private val exportExecutor: ExportExecutor,
    private val documentRetraction: DocumentRetraction,
    private val documentTombstone: DocumentTombstone,
    private val verificationSignals: VerificationSignals,
    private val requestPublicView: RequestPublicView,
    private val codes: DsrVerificationCodes,
    private val mail: MailPort,
    @Qualifier("dsrCodeLimiter") private val codeLimiter: SlidingWindowRateLimiter,
) {

    // ---- account-holder channel ----

    @Transactional
    fun create(user: AuthenticatedUser, req: CreateDataSubjectRequestRequest): DataSubjectRequestResponse {
        // Consent withdrawal is a recommender right (GDPR Art. 7(3)); an account holder has no
        // consent to withdraw. It is only reachable via the account-less recommender email channel,
        // so reject it here rather than let it flip to EXECUTED with no effect.
        if (req.type == DsrType.CONSENT_WITHDRAWAL) {
            throw ApiException(
                HttpStatus.CONFLICT, "CONSENT_WITHDRAWAL_NOT_APPLICABLE",
                "Consent withdrawal is available only to recommenders via the account-less email channel",
            )
        }
        val now = OffsetDateTime.now()
        val dsr = DATA_SUBJECT_REQUEST
        val record = dsl.insertInto(dsr)
            .set(dsr.ID, UUID.randomUUID())
            .set(dsr.TYPE, req.type!!.name)
            .set(dsr.STATUS, DsrStatus.RECEIVED.name)
            .set(dsr.REGION, user.region)
            .set(dsr.SUBJECT_EMAIL, user.email)
            .set(dsr.USER_ID, user.userId)
            // Session identity is the verification: verified at creation.
            .set(dsr.VERIFIED_AT, now)
            .set(dsr.DUE_AT, now.plus(props.privacy.sla))
            .set(dsr.RESOLUTION_NOTES, req.comment)
            .returning()
            .fetchOne()!!

        auditReceived(record, actorType = "USER", actorId = user.userId.toString())
        return record.toResponse()
    }

    @Transactional(readOnly = true)
    fun list(user: AuthenticatedUser, cursor: String?): DataSubjectRequestListResponse {
        val dsr = DATA_SUBJECT_REQUEST
        val cursorCondition = if (cursor != null) {
            val (ts, id) = decodeCursor(cursor)
            DSL.row(dsr.CREATED_AT, dsr.ID).gt(ts, id)
        } else {
            DSL.noCondition()
        }
        val rows = dsl.selectFrom(dsr)
            .where(dsr.USER_ID.eq(user.userId).and(cursorCondition))
            .orderBy(dsr.CREATED_AT.asc(), dsr.ID.asc())
            .limit(PAGE_SIZE + 1)
            .fetch()

        val hasMore = rows.size > PAGE_SIZE
        val items = if (hasMore) rows.take(PAGE_SIZE) else rows
        val nextCursor = if (hasMore) items.last().let { encodeCursor(it.createdAt!!, it.id!!) } else null
        return DataSubjectRequestListResponse(items.map { it.toResponse() }, nextCursor)
    }

    // ---- account-less recommender channel ----

    /**
     * Anti-enumeration intake: always a no-op-safe unit. If [email] matches any reference
     * request (snapshot email or contact email) in this cell, creates ONE unverified DSR
     * (subject = the newest matching contact) and emails a 6-digit code with a verification link.
     * Unknown emails silently do nothing — the caller answers 202 either way.
     */
    @Transactional
    fun submitRecommenderRequest(email: String) {
        val refs = consentWithdrawal.findRequestsByRecommenderEmail(email)
        if (refs.isEmpty()) return
        // Per-email code limiter (3/15min); a throttled email is a silent no-op (still 202).
        if (!codeLimiter.tryAcquire(email.trim().lowercase())) return

        val now = OffsetDateTime.now()
        val dsr = DATA_SUBJECT_REQUEST
        val record = dsl.insertInto(dsr)
            .set(dsr.ID, UUID.randomUUID())
            // Default to CONSENT_WITHDRAWAL (the primary recommender request); overwritten at verify.
            .set(dsr.TYPE, DsrType.CONSENT_WITHDRAWAL.name)
            .set(dsr.STATUS, DsrStatus.RECEIVED.name)
            .set(dsr.REGION, props.region)
            .set(dsr.SUBJECT_EMAIL, email.trim())
            .set(dsr.RECOMMENDER_CONTACT_ID, refs.first().recommenderContactId)
            .set(dsr.DUE_AT, now.plus(props.privacy.sla))
            .returning()
            .fetchOne()!!

        auditReceived(record, actorType = "RECOMMENDER", actorId = null)

        val rawCode = codes.issue(record.id!!)
        val base = props.auth.frontendBaseUrl
        val ttlMinutes = 10L
        mail.send(
            to = email.trim(),
            subject = "Your Verifolio data request code",
            textBody = buildString {
                appendLine("You (or someone using your email) asked to exercise a data right at Verifolio.")
                appendLine()
                appendLine("Verification code: $rawCode")
                appendLine("Continue here: $base/data-requests/${record.id}")
                appendLine()
                appendLine("The code expires in $ttlMinutes minutes. If you did not request this, ignore this email.")
            },
        )
    }

    /**
     * Verifies the emailed code, records the requested [type] (and optional single-request scope),
     * and — for CONSENT_WITHDRAWAL — executes immediately. Other types stay RECEIVED for manual
     * handling. CODE_INVALID (400) on a bad/expired code; 404 for an unknown/consumed request.
     */
    @Transactional
    fun verifyRecommenderRequest(
        dsrId: UUID,
        code: String,
        type: DsrType,
        referenceRequestId: UUID?,
    ): RecommenderDsrVerifyResponse {
        val dsr = DATA_SUBJECT_REQUEST
        val record = dsl.selectFrom(dsr).where(dsr.ID.eq(dsrId)).forUpdate().fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Data request not found")
        // Recommender DSRs only; an already-verified/terminal row is not re-verifiable.
        if (record.userId != null || record.verifiedAt != null ||
            DsrStatus.valueOf(record.status!!) != DsrStatus.RECEIVED
        ) {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_REQUEST_STATE", "Request cannot be verified")
        }

        codes.verify(dsrId, code)

        dsl.update(dsr)
            .set(dsr.TYPE, type.name)
            .set(dsr.VERIFIED_AT, OffsetDateTime.now())
            .set(dsr.REFERENCE_REQUEST_ID, referenceRequestId)
            .set(dsr.UPDATED_AT, OffsetDateTime.now())
            .where(dsr.ID.eq(dsrId))
            .execute()

        return if (type == DsrType.CONSENT_WITHDRAWAL) {
            execute(dsrId)
            RecommenderDsrVerifyResponse(status = DsrStatus.EXECUTED.name, executed = true, dueAt = null)
        } else {
            RecommenderDsrVerifyResponse(
                status = DsrStatus.RECEIVED.name,
                executed = false,
                dueAt = record.dueAt!!.toString(),
            )
        }
    }

    // ---- hybrid execution (ops entry point; no HTTP) ----

    /**
     * Executes a DSR. Implemented for CONSENT_WITHDRAWAL (across the subject's requests, or the
     * scoped one) and DELETION of a recommender subject scoped to a reference request (tombstone
     * + erasure). Other types are deferred to the admin/automation iteration and surface as
     * 409 EXECUTION_NOT_AUTOMATED so the admin UI shows "manual execution required" rather than 500.
     *
     * [adminActorId] is the acting admin's id when triggered from the admin console (audited as the
     * ADMIN actor); null for the recommender-channel auto-execute path (audited as SYSTEM/USER).
     */
    @Transactional
    fun execute(dsrId: UUID, adminActorId: String? = null) {
        val dsr = DATA_SUBJECT_REQUEST
        val record = dsl.selectFrom(dsr).where(dsr.ID.eq(dsrId)).forUpdate().fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Data request not found")
        val status = DsrStatus.valueOf(record.status!!)
        if (!status.canTransitionTo(DsrStatus.EXECUTED)) {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_REQUEST_STATE", "Request is not executable")
        }

        when (DsrType.valueOf(record.type!!)) {
            DsrType.CONSENT_WITHDRAWAL -> {
                val refs = scopeForRecommender(record)
                // Never mark EXECUTED when nothing was actually withdrawn (defends against a
                // withdrawal that resolves to no in-scope recommender requests).
                if (refs.isEmpty()) {
                    throw ApiException(
                        HttpStatus.CONFLICT, "NOTHING_TO_EXECUTE",
                        "No recommender requests are in scope for this consent withdrawal",
                    )
                }
                executor.execute(refs)
            }
            DsrType.DELETION -> {
                // Only recommender-subject, request-scoped deletion executes here; whole-account
                // holder deletion is deferred to the admin/automation iteration.
                if (record.recommenderContactId == null) {
                    throw ApiException(
                        HttpStatus.CONFLICT, "EXECUTION_NOT_AUTOMATED",
                        "Account-holder deletion execution is not automated yet; manual execution required",
                    )
                }
                scopeForRecommender(record).forEach { ref ->
                    val versionIds = documentRetraction.versionIdsForRequest(ref.requestId)
                    // Revoke the version + response verification signals BEFORE erasure (mirror the
                    // consent-withdrawal path) so tombstoned content keeps no lingering active badge.
                    versionIds.forEach { verificationSignals.revokeAllForEntity("DOCUMENT_VERSION", it) }
                    requestPublicView.latestResponseId(ref.requestId)?.let { responseId ->
                        verificationSignals.revokeAllForEntity("REFERENCE_RESPONSE", responseId)
                    }
                    versionIds.forEach { documentTombstone.tombstone(it) }
                    recommenderPiiErasure.eraseForRequest(ref.requestId)
                }
            }
            DsrType.EXPORT -> exportExecutor.execute(record, adminActorId)
            else -> throw ApiException(
                HttpStatus.CONFLICT, "EXECUTION_NOT_AUTOMATED",
                "Execution for ${record.type} is not automated yet; manual execution required",
            )
        }

        dsl.update(dsr)
            .set(dsr.STATUS, DsrStatus.EXECUTED.name)
            .set(dsr.UPDATED_AT, OffsetDateTime.now())
            .where(dsr.ID.eq(dsrId))
            .execute()
        // An admin-triggered execution records the ADMIN actor; the auto path keeps SYSTEM/USER.
        val actorType = when {
            adminActorId != null -> "ADMIN"
            record.userId != null -> "USER"
            else -> "SYSTEM"
        }
        audit.record(
            actorType = actorType,
            actorId = adminActorId ?: record.userId?.toString(),
            action = "DATA_SUBJECT_REQUEST_EXECUTED",
            entityType = "DATA_SUBJECT_REQUEST",
            entityId = dsrId.toString(),
            metadata = mapOf("type" to record.type!!, "previousStatus" to status.name),
        )
    }

    /**
     * Admin decision transitions (admin console). [adminActorId] is the acting admin's id — the
     * DATA_SUBJECT_REQUEST_APPROVED/REJECTED audit records the ADMIN actor.
     */
    @Transactional
    fun approve(dsrId: UUID, adminActorId: String) =
        transition(dsrId, DsrStatus.APPROVED, "DATA_SUBJECT_REQUEST_APPROVED", adminActorId, null)

    @Transactional
    fun reject(dsrId: UUID, adminActorId: String, notes: String?) =
        transition(dsrId, DsrStatus.REJECTED, "DATA_SUBJECT_REQUEST_REJECTED", adminActorId, notes)

    // ---- helpers ----

    private fun transition(dsrId: UUID, target: DsrStatus, action: String, adminActorId: String, notes: String?) {
        val dsr = DATA_SUBJECT_REQUEST
        val record = dsl.selectFrom(dsr).where(dsr.ID.eq(dsrId)).forUpdate().fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Data request not found")
        val status = DsrStatus.valueOf(record.status!!)
        if (!status.canTransitionTo(target)) {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_REQUEST_STATE", "Illegal transition $status -> $target")
        }
        var update = dsl.update(dsr)
            .set(dsr.STATUS, target.name)
            .set(dsr.UPDATED_AT, OffsetDateTime.now())
        if (notes != null) update = update.set(dsr.RESOLUTION_NOTES, notes)
        update.where(dsr.ID.eq(dsrId)).execute()
        audit.record(
            actorType = "ADMIN",
            actorId = adminActorId,
            action = action,
            entityType = "DATA_SUBJECT_REQUEST",
            entityId = dsrId.toString(),
            metadata = mapOf("previousStatus" to status.name),
        )
    }

    /** The subject's reference requests, narrowed to a single one when the DSR carries a scope. */
    private fun scopeForRecommender(record: DataSubjectRequestRecord): List<RecommenderRequestRef> {
        val all = consentWithdrawal.findRequestsByRecommenderEmail(record.subjectEmail!!)
        val scoped = record.referenceRequestId
        return if (scoped != null) all.filter { it.requestId == scoped } else all
    }

    private fun auditReceived(record: DataSubjectRequestRecord, actorType: String, actorId: String?) {
        audit.record(
            actorType = actorType,
            actorId = actorId,
            action = "DATA_SUBJECT_REQUEST_RECEIVED",
            entityType = "DATA_SUBJECT_REQUEST",
            entityId = record.id.toString(),
            metadata = mapOf("type" to record.type!!, "region" to record.region!!),
        )
    }

    private fun encodeCursor(createdAt: OffsetDateTime, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAt|$id".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(cursor: String): Pair<OffsetDateTime, UUID> = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        val idx = decoded.lastIndexOf('|')
        require(idx > 0)
        OffsetDateTime.parse(decoded.substring(0, idx)) to UUID.fromString(decoded.substring(idx + 1))
    }.getOrElse { throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid cursor") }

    private fun DataSubjectRequestRecord.toResponse() = DataSubjectRequestResponse(
        id = id!!.toString(),
        type = type!!,
        status = status!!,
        subjectEmail = subjectEmail!!,
        dueAt = dueAt!!.toString(),
        verifiedAt = verifiedAt?.toString(),
        resolutionNotes = resolutionNotes,
        createdAt = createdAt!!.toString(),
        updatedAt = updatedAt?.toString(),
    )
}
