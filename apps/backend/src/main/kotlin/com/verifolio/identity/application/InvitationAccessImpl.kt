package com.verifolio.identity.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.InvitationAccess
import com.verifolio.identity.InvitationInfo
import com.verifolio.identity.RecommenderGrant
import com.verifolio.identity.domain.TokenGenerator
import com.verifolio.identity.domain.TokenHasher
import com.verifolio.jooq.tables.records.InvitationTokenRecord
import com.verifolio.jooq.tables.references.EMAIL_CONFIRMATION_CODE
import com.verifolio.jooq.tables.references.INVITATION_TOKEN
import com.verifolio.jooq.tables.references.RECOMMENDER_SESSION
import com.verifolio.platform.ApiException
import com.verifolio.platform.VerifolioProperties
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

private const val MAX_CODE_ATTEMPTS = 5

@Service
internal class InvitationAccessImpl(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val audit: AuditService,
    private val props: VerifolioProperties,
    private val attemptRecorder: ConfirmationAttemptRecorder,
) : InvitationAccess {

    private val random = SecureRandom()

    @Transactional(readOnly = true)
    override fun peek(rawToken: String): InvitationInfo? =
        findByHash(rawToken)
            ?.takeIf { it.consumedAt == null && it.revokedAt == null && it.expiresAt!!.isAfter(OffsetDateTime.now()) }
            ?.let { InvitationInfo(it.requestId!!, it.recommenderEmail!!) }

    @Transactional(readOnly = true)
    override fun identify(rawToken: String): InvitationInfo? =
        findByHash(rawToken)?.let { InvitationInfo(it.requestId!!, it.recommenderEmail!!) }

    @Transactional
    override fun issueEmailConfirmation(rawToken: String): String {
        val token = findValidToken(rawToken)

        val rawCode = "%06d".format(random.nextInt(1_000_000))
        val ecc = EMAIL_CONFIRMATION_CODE
        dsl.insertInto(ecc)
            .set(ecc.INVITATION_TOKEN_ID, token.id)
            .set(ecc.CODE_HASH, hasher.hash(rawCode))
            .set(ecc.EXPIRES_AT, OffsetDateTime.now().plus(props.auth.emailConfirmationTtl))
            .execute()
        return rawCode
    }

    @Transactional
    override fun confirmEmail(
        rawToken: String,
        code: String,
        rawIp: String?,
        rawUserAgent: String?,
    ): RecommenderGrant {
        val token = findValidToken(rawToken)
        val now = OffsetDateTime.now()
        val ipHash = rawIp?.let { hasher.hash(it) }
        val userAgentHash = rawUserAgent?.let { hasher.hash(it) }

        val ecc = EMAIL_CONFIRMATION_CODE
        // No FOR UPDATE here: the failed-attempt increment runs in a REQUIRES_NEW
        // transaction (see ConfirmationAttemptRecorder) and would deadlock against a
        // row lock held by this transaction. The increment itself is atomic.
        val codeRow = dsl.selectFrom(ecc)
            .where(ecc.INVITATION_TOKEN_ID.eq(token.id).and(ecc.CONSUMED_AT.isNull))
            .orderBy(ecc.CREATED_AT.desc())
            .limit(1)
            .fetchOne()
            ?: throw codeInvalid()

        if (!codeRow.expiresAt!!.isAfter(now) || codeRow.attempts!! >= MAX_CODE_ATTEMPTS) throw codeInvalid()

        if (codeRow.codeHash != hasher.hash(code)) {
            // REQUIRES_NEW so the increment survives this transaction's rollback.
            attemptRecorder.recordFailure(codeRow.id!!)
            throw codeInvalid()
        }

        // Conditional consumption: a concurrent confirm that committed first leaves zero
        // affected rows here, so the second request fails instead of minting a duplicate
        // session (single-use guarantee, docs/AUTHENTICATION.md).
        val codeConsumed = dsl.update(ecc)
            .set(ecc.CONSUMED_AT, now)
            .where(ecc.ID.eq(codeRow.id).and(ecc.CONSUMED_AT.isNull))
            .execute()
        if (codeConsumed == 0) throw codeInvalid()

        val it = INVITATION_TOKEN
        val tokenConsumed = dsl.update(it)
            .set(it.CONSUMED_AT, now)
            .where(it.ID.eq(token.id).and(it.CONSUMED_AT.isNull))
            .execute()
        if (tokenConsumed == 0) throw codeInvalid()

        val rawSessionToken = TokenGenerator.generate()
        val rs = RECOMMENDER_SESSION
        dsl.insertInto(rs)
            .set(rs.REQUEST_ID, token.requestId)
            .set(rs.RECOMMENDER_EMAIL, token.recommenderEmail)
            .set(rs.TOKEN_HASH, hasher.hash(rawSessionToken))
            .set(rs.IP_HASH, ipHash)
            .set(rs.USER_AGENT_HASH, userAgentHash)
            .set(rs.EXPIRES_AT, now.plus(props.auth.recommenderSessionTtl))
            .execute()

        audit.record(
            actorType = "RECOMMENDER",
            actorId = null,
            action = "RECOMMENDER_EMAIL_CONFIRMED",
            entityType = "REFERENCE_REQUEST",
            entityId = token.requestId.toString(),
            ipHash = ipHash,
            userAgentHash = userAgentHash,
        )
        audit.record(
            actorType = "RECOMMENDER",
            actorId = null,
            action = "INVITATION_TOKEN_CONSUMED",
            entityType = "INVITATION_TOKEN",
            entityId = token.id.toString(),
            metadata = mapOf("requestId" to token.requestId.toString()),
        )

        return RecommenderGrant(rawSessionToken, token.requestId!!, token.recommenderEmail!!)
    }

    // ---- helpers ----

    private fun findByHash(rawToken: String): InvitationTokenRecord? =
        dsl.selectFrom(INVITATION_TOKEN)
            .where(INVITATION_TOKEN.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .fetchOne()

    private fun findValidToken(rawToken: String): InvitationTokenRecord =
        findByHash(rawToken)
            ?.takeIf { it.consumedAt == null && it.revokedAt == null && it.expiresAt!!.isAfter(OffsetDateTime.now()) }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Invitation not found")

    private fun codeInvalid() =
        ApiException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "The confirmation code is invalid or expired")
}
