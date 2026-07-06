package com.verifolio.identity

import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.jooq.tables.references.USER_SESSION
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

/** Identity admin read model: region-scoped user list (search/filter/paging) + user card. */
class UserAdminViewTest : IntegrationTest() {

    @Autowired lateinit var view: UserAdminView
    @Autowired lateinit var dsl: DSLContext

    private fun seedUser(
        email: String,
        region: String,
        status: String = "ACTIVE",
        displayName: String? = null,
        createdAt: OffsetDateTime = OffsetDateTime.now(),
    ): UUID {
        val id = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, id)
            .set(ua.EMAIL, email)
            .set(ua.REGION, region)
            .set(ua.STATUS, status)
            .set(ua.CREATED_AT, createdAt)
            .execute()
        if (displayName != null) {
            val pp = PERSON_PROFILE
            dsl.insertInto(pp)
                .set(pp.USER_ACCOUNT_ID, id)
                .set(pp.DISPLAY_NAME, displayName)
                .execute()
        }
        return id
    }

    private fun seedSession(userId: UUID, createdAt: OffsetDateTime) {
        val us = USER_SESSION
        dsl.insertInto(us)
            .set(us.ID, UUID.randomUUID())
            .set(us.USER_ACCOUNT_ID, userId)
            .set(us.TOKEN_HASH, "hash-${UUID.randomUUID()}")
            .set(us.IP_HASH, "ip-hash-${UUID.randomUUID()}")
            .set(us.USER_AGENT_HASH, "ua-hash-${UUID.randomUUID()}")
            .set(us.EXPIRES_AT, createdAt.plusDays(30))
            .set(us.CREATED_AT, createdAt)
            .execute()
    }

    @Test
    fun `list is region-scoped, newest-first, with joined display name`() {
        val tag = UUID.randomUUID().toString().take(8)
        val base = OffsetDateTime.now()
        val a1 = seedUser("aa-$tag@example.com", "RA-$tag", displayName = "Alice $tag", createdAt = base)
        val a2 = seedUser("bb-$tag@example.com", "RA-$tag", createdAt = base.plusSeconds(1))
        val a3 = seedUser("cc-$tag@example.com", "RA-$tag", status = "DISABLED", createdAt = base.plusSeconds(2))
        seedUser("dd-$tag@example.com", "RB-$tag", displayName = "Bob $tag", createdAt = base.plusSeconds(3))

        val page = view.list("RA-$tag", null, null, null)

        assertThat(page.items.map { it.id }).containsExactly(a3, a2, a1) // newest-first, region B excluded
        assertThat(page.nextCursor).isNull()
        assertThat(page.items.first { it.id == a1 }.displayName).isEqualTo("Alice $tag")
        assertThat(page.items.first { it.id == a2 }.displayName).isNull() // no profile
    }

    @Test
    fun `query filter matches email or display name prefix`() {
        val tag = UUID.randomUUID().toString().take(8)
        val byEmail = seedUser("zephyr-$tag@example.com", "RQ-$tag")
        val byName = seedUser("other-$tag@example.com", "RQ-$tag", displayName = "Zephyrine $tag")
        seedUser("nomatch-$tag@example.com", "RQ-$tag", displayName = "Nobody")

        val page = view.list("RQ-$tag", "zephyr", null, null)

        assertThat(page.items.map { it.id }).containsExactlyInAnyOrder(byEmail, byName)
    }

    @Test
    fun `status filter is exact`() {
        val tag = UUID.randomUUID().toString().take(8)
        seedUser("act-$tag@example.com", "RS-$tag", status = "ACTIVE")
        val disabled = seedUser("dis-$tag@example.com", "RS-$tag", status = "DISABLED")

        val page = view.list("RS-$tag", null, "DISABLED", null)

        assertThat(page.items.map { it.id }).containsExactly(disabled)
    }

    @Test
    fun `keyset paging returns 50 then the remaining 5`() {
        val tag = UUID.randomUUID().toString().take(8)
        val region = "RP-$tag"
        val base = OffsetDateTime.now()
        repeat(55) { i -> seedUser("u$i-$tag@example.com", region, createdAt = base.plusSeconds(i.toLong())) }

        val first = view.list(region, null, null, null)
        assertThat(first.items).hasSize(50)
        assertThat(first.nextCursor).isNotNull()

        val second = view.list(region, null, null, first.nextCursor)
        assertThat(second.items).hasSize(5)
        assertThat(second.nextCursor).isNull()

        val allIds = (first.items + second.items).map { it.id }
        assertThat(allIds).doesNotHaveDuplicates().hasSize(55)
    }

    @Test
    fun `card returns account plus sessions newest-first without ip or ua hashes`() {
        val tag = UUID.randomUUID().toString().take(8)
        val region = "RC-$tag"
        val base = OffsetDateTime.now()
        val userId = seedUser("card-$tag@example.com", region, status = "ACTIVE", createdAt = base)
        seedSession(userId, base.plusSeconds(1))
        seedSession(userId, base.plusSeconds(2))

        val card = view.card(userId, region)

        assertThat(card).isNotNull()
        assertThat(card!!.account.email).isEqualTo("card-$tag@example.com")
        assertThat(card.account.region).isEqualTo(region)
        assertThat(card.account.status).isEqualTo("ACTIVE")
        assertThat(card.account.deletedAt).isNull()
        assertThat(card.sessions).hasSize(2)
        // Newest-first.
        assertThat(card.sessions[0].createdAt).isAfterOrEqualTo(card.sessions[1].createdAt)
        // No ip/ua hash leaks — the DTO simply has no such fields (reflection sanity check).
        val fieldNames = UserAdminSession::class.java.declaredFields.map { it.name }
        assertThat(fieldNames).doesNotContain("ipHash", "userAgentHash")
    }

    @Test
    fun `card for a foreign-region user is null`() {
        val tag = UUID.randomUUID().toString().take(8)
        val userB = seedUser("foreign-$tag@example.com", "RB-$tag")

        assertThat(view.card(userB, "RA-$tag")).isNull()
    }
}
