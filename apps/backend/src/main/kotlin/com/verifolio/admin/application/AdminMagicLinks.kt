package com.verifolio.admin.application

import com.verifolio.admin.domain.AdminAccount
import com.verifolio.jooq.tables.references.ADMIN_MAGIC_LINK_TOKEN
import com.verifolio.platform.TokenGenerator
import com.verifolio.platform.TokenHasher
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Admin magic-link tokens (spec §Flow step 1-2), mirroring identity MagicLinkService: the raw
 * token is returned to the caller (to email) and only its HMAC hash is stored; 15-min TTL;
 * single-use; reissue invalidates prior unconsumed tokens for the email. Anti-enumeration (the
 * always-202 response, rate limiting) is handled by the Task 3 controller — mint is only called
 * for an email that is an ACTIVE admin in this cell.
 */
@Service
internal class AdminMagicLinks(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val accounts: AdminAccounts,
) {

    /** Mints a fresh single-use token for [rawEmail]; returns the raw token to email. */
    @Transactional
    fun mint(rawEmail: String): String {
        val email = rawEmail.trim().lowercase()
        val now = OffsetDateTime.now()

        // Reissue invalidates all prior unconsumed tokens for this email.
        dsl.update(ADMIN_MAGIC_LINK_TOKEN)
            .set(ADMIN_MAGIC_LINK_TOKEN.INVALIDATED_AT, now)
            .where(ADMIN_MAGIC_LINK_TOKEN.EMAIL.eq(email))
            .and(ADMIN_MAGIC_LINK_TOKEN.CONSUMED_AT.isNull)
            .and(ADMIN_MAGIC_LINK_TOKEN.INVALIDATED_AT.isNull)
            .execute()

        val raw = TokenGenerator.generate()
        dsl.insertInto(ADMIN_MAGIC_LINK_TOKEN)
            .set(ADMIN_MAGIC_LINK_TOKEN.EMAIL, email)
            .set(ADMIN_MAGIC_LINK_TOKEN.TOKEN_HASH, hasher.hash(raw))
            .set(ADMIN_MAGIC_LINK_TOKEN.EXPIRES_AT, now.plus(TTL))
            .execute()
        return raw
    }

    /**
     * Validates + consumes [rawToken] (must be unexpired, unconsumed, not invalidated). On success
     * marks it consumed (single-use) and returns the matching ACTIVE admin_account, or null when
     * the token is invalid or no ACTIVE admin exists for its email.
     */
    @Transactional
    fun consume(rawToken: String): AdminAccount? {
        val now = OffsetDateTime.now()
        val row = dsl.selectFrom(ADMIN_MAGIC_LINK_TOKEN)
            .where(ADMIN_MAGIC_LINK_TOKEN.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .and(ADMIN_MAGIC_LINK_TOKEN.CONSUMED_AT.isNull)
            .and(ADMIN_MAGIC_LINK_TOKEN.INVALIDATED_AT.isNull)
            .and(ADMIN_MAGIC_LINK_TOKEN.EXPIRES_AT.gt(now))
            .forUpdate()
            .fetchOne() ?: return null

        dsl.update(ADMIN_MAGIC_LINK_TOKEN)
            .set(ADMIN_MAGIC_LINK_TOKEN.CONSUMED_AT, now)
            .where(ADMIN_MAGIC_LINK_TOKEN.ID.eq(row.id))
            .execute()

        return accounts.activeByEmail(row.email!!)
    }

    private companion object {
        val TTL: Duration = Duration.ofMinutes(15)
    }
}
