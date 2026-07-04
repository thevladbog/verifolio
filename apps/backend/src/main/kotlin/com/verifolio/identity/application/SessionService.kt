package com.verifolio.identity.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.domain.TokenGenerator
import com.verifolio.identity.domain.TokenHasher
import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.jooq.tables.references.USER_SESSION
import com.verifolio.platform.VerifolioProperties
import com.verifolio.platform.ApiException
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

data class AuthenticatedUser(val userId: UUID, val email: String, val region: String)
data class CreatedSession(val rawToken: String, val user: AuthenticatedUser, val ttlSeconds: Long)

@Service
class SessionService(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val audit: AuditService,
    private val props: VerifolioProperties,
) {

    @Transactional
    fun consumeMagicLink(rawToken: String, ipHash: String?, userAgentHash: String?): CreatedSession {
        val now = OffsetDateTime.now()
        val tokenRow = dsl.selectFrom(MAGIC_LINK_TOKEN)
            .where(MAGIC_LINK_TOKEN.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .and(MAGIC_LINK_TOKEN.CONSUMED_AT.isNull)
            .and(MAGIC_LINK_TOKEN.INVALIDATED_AT.isNull)
            .and(MAGIC_LINK_TOKEN.EXPIRES_AT.gt(now))
            .forUpdate()
            .fetchOne()
            ?: run {
                audit.record(
                    actorType = "USER",
                    actorId = null,
                    action = "LOGIN_FAILED",
                    entityType = "MAGIC_LINK_TOKEN",
                    metadata = mapOf("reason" to "invalid_or_expired_token"),
                    ipHash = ipHash,
                    userAgentHash = userAgentHash,
                )
                throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid or expired token")
            }

        dsl.update(MAGIC_LINK_TOKEN)
            .set(MAGIC_LINK_TOKEN.CONSUMED_AT, now)
            .where(MAGIC_LINK_TOKEN.ID.eq(tokenRow.id))
            .execute()

        val account = dsl.selectFrom(USER_ACCOUNT)
            .where(USER_ACCOUNT.EMAIL.eq(tokenRow.email))
            .fetchOne()
            ?: dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, tokenRow.email)
                .set(USER_ACCOUNT.REGION, props.region)
                .returning()
                .fetchOne()!!

        val sessionToken = TokenGenerator.generate()
        val sessionRow = dsl.insertInto(USER_SESSION)
            .set(USER_SESSION.USER_ACCOUNT_ID, account.id)
            .set(USER_SESSION.TOKEN_HASH, hasher.hash(sessionToken))
            .set(USER_SESSION.IP_HASH, ipHash)
            .set(USER_SESSION.USER_AGENT_HASH, userAgentHash)
            .set(USER_SESSION.EXPIRES_AT, now.plus(props.auth.sessionTtl))
            .returning(USER_SESSION.ID)
            .fetchOne()!!

        val user = AuthenticatedUser(account.id!!, account.email!!, account.region!!)
        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "MAGIC_LINK_CONSUMED",
            entityType = "MAGIC_LINK_TOKEN",
            entityId = tokenRow.id.toString(),
            ipHash = ipHash,
            userAgentHash = userAgentHash,
        )
        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "LOGIN_SUCCEEDED",
            entityType = "USER_ACCOUNT",
            entityId = user.userId.toString(),
            ipHash = ipHash,
            userAgentHash = userAgentHash,
        )
        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "SESSION_CREATED",
            entityType = "SESSION",
            entityId = sessionRow.id.toString(),
            ipHash = ipHash,
            userAgentHash = userAgentHash,
        )
        return CreatedSession(sessionToken, user, props.auth.sessionTtl.seconds)
    }

    @Transactional(readOnly = true)
    fun resolve(rawToken: String): AuthenticatedUser? {
        val now = OffsetDateTime.now()
        return dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.EMAIL, USER_ACCOUNT.REGION)
            .from(USER_SESSION)
            .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(USER_SESSION.USER_ACCOUNT_ID))
            .where(USER_SESSION.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .and(USER_SESSION.REVOKED_AT.isNull)
            .and(USER_SESSION.EXPIRES_AT.gt(now))
            .fetchOne()
            ?.let { AuthenticatedUser(it.value1()!!, it.value2()!!, it.value3()!!) }
    }

    @Transactional
    fun revoke(rawToken: String, ipHash: String?, userAgentHash: String?) {
        val revokedRow = dsl.update(USER_SESSION)
            .set(USER_SESSION.REVOKED_AT, OffsetDateTime.now())
            .where(USER_SESSION.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .and(USER_SESSION.REVOKED_AT.isNull)
            .returning(USER_SESSION.USER_ACCOUNT_ID)
            .fetchOne()
        if (revokedRow != null) {
            val actorId = revokedRow.userAccountId.toString()
            // SESSION_REVOKED and LOGOUT are distinct events per docs/AUDIT_EVENTS.md:
            // SESSION_REVOKED tracks the session lifecycle; LOGOUT tracks the user action.
            audit.record(
                actorType = "USER",
                actorId = actorId,
                action = "SESSION_REVOKED",
                entityType = "SESSION",
                ipHash = ipHash,
                userAgentHash = userAgentHash,
            )
            audit.record(
                actorType = "USER",
                actorId = actorId,
                action = "LOGOUT",
                entityType = "SESSION",
                ipHash = ipHash,
                userAgentHash = userAgentHash,
            )
        }
    }
}
