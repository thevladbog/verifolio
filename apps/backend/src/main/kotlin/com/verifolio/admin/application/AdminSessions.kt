package com.verifolio.admin.application

import com.verifolio.admin.AdminActor
import com.verifolio.admin.domain.AdminRole
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import com.verifolio.jooq.tables.references.ADMIN_SESSION
import com.verifolio.platform.TokenGenerator
import com.verifolio.platform.TokenHasher
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** A freshly minted admin session: the raw cookie value and its TTL in seconds. */
data class CreatedAdminSession(val rawToken: String, val ttlSeconds: Long)

/**
 * Admin sessions (spec §Flow, §Schema `admin_session`), isolated from user sessions and mirroring
 * identity SessionService: only the HMAC hash of the token is stored; ip/ua are hashed. A session
 * is minted ONLY by the MFA flow (AdminMfa), never directly from a magic link — enforcing the
 * "session minted only after both factors" invariant. TTL is a fixed admin-session window (8h).
 */
@Service
internal class AdminSessions(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
) {

    /** Mints an admin session for [adminAccountId]. Caller is responsible for MFA success. */
    @Transactional
    fun mint(adminAccountId: UUID, ipHash: String?, userAgentHash: String?): CreatedAdminSession {
        val raw = TokenGenerator.generate()
        dsl.insertInto(ADMIN_SESSION)
            .set(ADMIN_SESSION.ADMIN_ACCOUNT_ID, adminAccountId)
            .set(ADMIN_SESSION.TOKEN_HASH, hasher.hash(raw))
            .set(ADMIN_SESSION.IP_HASH, ipHash)
            .set(ADMIN_SESSION.USER_AGENT_HASH, userAgentHash)
            .set(ADMIN_SESSION.EXPIRES_AT, OffsetDateTime.now().plus(TTL))
            .execute()
        return CreatedAdminSession(raw, TTL.seconds)
    }

    /** Resolves a valid (unrevoked, unexpired) session to its [AdminActor], or null. */
    @Transactional(readOnly = true)
    fun resolve(rawToken: String): AdminActor? {
        val now = OffsetDateTime.now()
        val row = dsl.select(
            ADMIN_ACCOUNT.ID,
            ADMIN_ACCOUNT.EMAIL,
            ADMIN_ACCOUNT.ROLE,
            ADMIN_ACCOUNT.REGION,
            ADMIN_ACCOUNT.STATUS,
        )
            .from(ADMIN_SESSION)
            .join(ADMIN_ACCOUNT).on(ADMIN_ACCOUNT.ID.eq(ADMIN_SESSION.ADMIN_ACCOUNT_ID))
            .where(ADMIN_SESSION.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .and(ADMIN_SESSION.REVOKED_AT.isNull)
            .and(ADMIN_SESSION.EXPIRES_AT.gt(now))
            .fetchOne() ?: return null

        if (row.get(ADMIN_ACCOUNT.STATUS) != "ACTIVE") return null
        return AdminActor(
            adminId = row.get(ADMIN_ACCOUNT.ID)!!,
            email = row.get(ADMIN_ACCOUNT.EMAIL)!!,
            role = AdminRole.valueOf(row.get(ADMIN_ACCOUNT.ROLE)!!),
            region = row.get(ADMIN_ACCOUNT.REGION)!!,
        )
    }

    /** Revokes the session for [rawToken] (idempotent). */
    @Transactional
    fun revoke(rawToken: String) {
        dsl.update(ADMIN_SESSION)
            .set(ADMIN_SESSION.REVOKED_AT, OffsetDateTime.now())
            .where(ADMIN_SESSION.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .and(ADMIN_SESSION.REVOKED_AT.isNull)
            .execute()
    }

    private companion object {
        val TTL: Duration = Duration.ofHours(8)
    }
}
