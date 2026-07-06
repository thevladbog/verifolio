package com.verifolio.identity

import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.jooq.tables.references.USER_SESSION
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

class AccountErasureIntegrationTest : IntegrationTest() {

    @Autowired lateinit var accountErasure: AccountErasure
    @Autowired lateinit var dsl: DSLContext

    private fun seedUser(email: String): UUID {
        val id = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, id).set(ua.EMAIL, email).set(ua.REGION, "EU").set(ua.STATUS, "ACTIVE")
            .execute()
        return id
    }

    private fun seedSession(userId: UUID) {
        val us = USER_SESSION
        dsl.insertInto(us)
            .set(us.ID, UUID.randomUUID())
            .set(us.USER_ACCOUNT_ID, userId)
            .set(us.TOKEN_HASH, "sess-${UUID.randomUUID()}")
            .set(us.EXPIRES_AT, OffsetDateTime.now().plusDays(1))
            .execute()
    }

    private fun seedMagicLink(email: String) {
        val ml = MAGIC_LINK_TOKEN
        dsl.insertInto(ml)
            .set(ml.ID, UUID.randomUUID())
            .set(ml.EMAIL, email)
            .set(ml.TOKEN_HASH, "ml-${UUID.randomUUID()}")
            .set(ml.EXPIRES_AT, OffsetDateTime.now().plusMinutes(15))
            .execute()
    }

    @Test
    fun `tombstones the account, drops sessions and magic links, and is idempotent`() {
        val email = "acct-erase-${UUID.randomUUID()}@example.com"
        val userId = seedUser(email)
        seedSession(userId)
        seedSession(userId)
        seedMagicLink(email)
        seedMagicLink(email)
        // A magic link for another email must survive.
        val otherEmail = "other-${UUID.randomUUID()}@example.com"
        seedMagicLink(otherEmail)

        accountErasure.eraseForUser(userId)

        val ua = USER_ACCOUNT
        val account = dsl.selectFrom(ua).where(ua.ID.eq(userId)).fetchOne()!!
        assertThat(account.status).isEqualTo("DELETED")
        assertThat(account.deletedAt).isNotNull()
        assertThat(account.email).isEqualTo("deleted-$userId@tombstone.invalid")

        assertThat(
            dsl.fetchCount(USER_SESSION, USER_SESSION.USER_ACCOUNT_ID.eq(userId)),
        ).isZero()
        assertThat(
            dsl.fetchCount(MAGIC_LINK_TOKEN, MAGIC_LINK_TOKEN.EMAIL.eq(email)),
        ).isZero()
        // The other email's magic link is untouched.
        assertThat(
            dsl.fetchCount(MAGIC_LINK_TOKEN, MAGIC_LINK_TOKEN.EMAIL.eq(otherEmail)),
        ).isEqualTo(1)

        // Second call is a no-op (already DELETED): deleted_at unchanged.
        val deletedAtBefore = account.deletedAt
        accountErasure.eraseForUser(userId)
        val after = dsl.selectFrom(ua).where(ua.ID.eq(userId)).fetchOne()!!
        assertThat(after.status).isEqualTo("DELETED")
        assertThat(after.deletedAt).isEqualTo(deletedAtBefore)
        assertThat(after.email).isEqualTo("deleted-$userId@tombstone.invalid")
    }
}
