package com.verifolio.identity.application

import com.verifolio.identity.AccountErasure
import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.jooq.tables.references.USER_SESSION
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
internal class AccountErasureImpl(private val dsl: DSLContext) : AccountErasure {

    @Transactional
    override fun eraseForUser(userId: UUID) {
        val ua = USER_ACCOUNT
        val account = dsl.selectFrom(ua).where(ua.ID.eq(userId)).forUpdate().fetchOne() ?: return
        // Idempotent: an already-tombstoned account is a no-op.
        if (account.status == "DELETED") return

        // Capture the live email BEFORE overwriting so we can drop the magic links that were
        // minted for it (they key off email, not user id).
        val oldEmail = account.email

        // Tombstone the account row (RETAINED for FK integrity), strip the PII email.
        dsl.update(ua)
            .set(ua.STATUS, "DELETED")
            .set(ua.DELETED_AT, OffsetDateTime.now())
            .set(ua.EMAIL, "deleted-$userId@tombstone.invalid")
            .set(ua.UPDATED_AT, OffsetDateTime.now())
            .where(ua.ID.eq(userId))
            .execute()

        // Live credentials are deleted outright — no lawful basis to retain them.
        dsl.deleteFrom(USER_SESSION)
            .where(USER_SESSION.USER_ACCOUNT_ID.eq(userId))
            .execute()
        dsl.deleteFrom(MAGIC_LINK_TOKEN)
            .where(MAGIC_LINK_TOKEN.EMAIL.eq(oldEmail))
            .execute()
    }
}
