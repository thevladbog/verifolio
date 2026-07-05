package com.verifolio.identity.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.InvitationTokenService
import com.verifolio.platform.TokenGenerator
import com.verifolio.platform.TokenHasher
import com.verifolio.jooq.tables.references.INVITATION_TOKEN
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
internal class InvitationTokenServiceImpl(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val audit: AuditService,
) : InvitationTokenService {

    @Transactional
    override fun mint(requestId: UUID, recommenderEmail: String, ttl: Duration): String {
        val raw = TokenGenerator.generate()
        val it = INVITATION_TOKEN
        dsl.insertInto(it)
            .set(it.REQUEST_ID, requestId)
            .set(it.RECOMMENDER_EMAIL, recommenderEmail)
            .set(it.TOKEN_HASH, hasher.hash(raw))
            .set(it.EXPIRES_AT, OffsetDateTime.now().plus(ttl))
            .execute()
        return raw
    }

    @Transactional
    override fun revokeForRequest(requestId: UUID, createdBefore: OffsetDateTime?): Int {
        val it = INVITATION_TOKEN
        var condition = it.REQUEST_ID.eq(requestId).and(it.CONSUMED_AT.isNull).and(it.REVOKED_AT.isNull)
        if (createdBefore != null) condition = condition.and(it.CREATED_AT.lt(createdBefore))
        val revoked = dsl.update(it)
            .set(it.REVOKED_AT, OffsetDateTime.now())
            .where(condition)
            .returning(it.ID)
            .fetch()
        revoked.forEach { row ->
            audit.record(
                actorType = "SYSTEM",
                actorId = null,
                action = "INVITATION_TOKEN_REVOKED",
                entityType = "INVITATION_TOKEN",
                entityId = row.id.toString(),
                metadata = mapOf("requestId" to requestId.toString()),
            )
        }
        return revoked.size
    }
}
