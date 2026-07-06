package com.verifolio.audit

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Audit admin read model: newest-first keyset paging over `audit_event`, optional filters
 * (actorType exact / action prefix / entityType exact / created_at range), jsonb metadata parsed,
 * and `ip_hash`/`user_agent_hash` never surfaced. The suite shares one DB with no truncation, so
 * every test isolates its rows behind a unique `actor_type` tag and filters on it.
 */
class AuditLogAdminViewTest : IntegrationTest() {

    @Autowired lateinit var view: AuditLogAdminView
    @Autowired lateinit var dsl: DSLContext

    private fun seed(
        actorType: String,
        action: String,
        entityType: String? = "SOME_ENTITY",
        createdAt: OffsetDateTime = OffsetDateTime.now(),
        metadataJson: String = "{}",
        ipHash: String? = "ip-secret",
        userAgentHash: String? = "ua-secret",
    ): UUID {
        val ae = AUDIT_EVENT
        return dsl.insertInto(ae)
            .set(ae.ID, UUID.randomUUID())
            .set(ae.ACTOR_TYPE, actorType)
            .set(ae.ACTOR_ID, UUID.randomUUID().toString())
            .set(ae.ACTION, action)
            .set(ae.ENTITY_TYPE, entityType)
            .set(ae.ENTITY_ID, UUID.randomUUID().toString())
            .set(ae.METADATA, JSONB.jsonb(metadataJson))
            .set(ae.IP_HASH, ipHash)
            .set(ae.USER_AGENT_HASH, userAgentHash)
            .set(ae.CREATED_AT, createdAt)
            .returning(ae.ID).fetchOne()!!.id!!
    }

    @Test
    fun `list is newest-first and pages via keyset (55 -- 50 + 5)`() {
        val tag = "PAGER-${UUID.randomUUID()}"
        val base = OffsetDateTime.now().minusHours(2)
        // 55 rows with strictly increasing created_at so DESC order is deterministic.
        repeat(55) { i -> seed(actorType = tag, action = "ACT_$i", createdAt = base.plusSeconds(i.toLong())) }

        val page1 = view.list(AuditFilters(actorType = tag), cursor = null)
        assertThat(page1.items).hasSize(50)
        assertThat(page1.nextCursor).isNotNull()
        // Newest-first: first item's created_at >= last item's.
        assertThat(page1.items.first().createdAt).isAfterOrEqualTo(page1.items.last().createdAt)

        val page2 = view.list(AuditFilters(actorType = tag), cursor = page1.nextCursor)
        assertThat(page2.items).hasSize(5)
        assertThat(page2.nextCursor).isNull()

        // No row appears on both pages, and the boundary is monotonic (page2 all older than page1 tail).
        val ids1 = page1.items.map { it.id }.toSet()
        assertThat(page2.items.map { it.id }).noneMatch { it in ids1 }
        assertThat(page2.items.first().createdAt).isBeforeOrEqualTo(page1.items.last().createdAt)
    }

    @Test
    fun `actorType filter is exact`() {
        val tag = "AT-${UUID.randomUUID()}"
        val other = "AT-${UUID.randomUUID()}"
        val mine = seed(actorType = tag, action = "X")
        seed(actorType = other, action = "X")

        val rows = view.list(AuditFilters(actorType = tag), null).items
        assertThat(rows.map { it.id }).containsExactly(mine)
        assertThat(rows).allMatch { it.actorType == tag }
    }

    @Test
    fun `action filter is a prefix match`() {
        val tag = "ACTPFX-${UUID.randomUUID()}"
        val hit = seed(actorType = tag, action = "LOGIN_SUCCEEDED")
        seed(actorType = tag, action = "LOGOUT")

        val rows = view.list(AuditFilters(actorType = tag, action = "LOGIN"), null).items
        assertThat(rows.map { it.id }).containsExactly(hit)
    }

    @Test
    fun `entityType filter is exact`() {
        val tag = "ET-${UUID.randomUUID()}"
        val hit = seed(actorType = tag, action = "X", entityType = "USER_ACCOUNT")
        seed(actorType = tag, action = "X", entityType = "DOCUMENT")

        val rows = view.list(AuditFilters(actorType = tag, entityType = "USER_ACCOUNT"), null).items
        assertThat(rows.map { it.id }).containsExactly(hit)
    }

    @Test
    fun `from and to bound the created_at range inclusively`() {
        val tag = "DR-${UUID.randomUUID()}"
        val t0 = OffsetDateTime.now().minusDays(10)
        val old = seed(actorType = tag, action = "X", createdAt = t0)
        // Rows exactly AT the from/to boundaries must be included (inclusive ge/le, not gt/lt).
        val atFrom = seed(actorType = tag, action = "X", createdAt = t0.plusDays(1))
        val mid = seed(actorType = tag, action = "X", createdAt = t0.plusDays(5))
        val atTo = seed(actorType = tag, action = "X", createdAt = t0.plusDays(6))
        val recent = seed(actorType = tag, action = "X", createdAt = t0.plusDays(9))

        val ranged = view.list(
            AuditFilters(actorType = tag, from = t0.plusDays(1), to = t0.plusDays(6)),
            null,
        ).items
        assertThat(ranged.map { it.id }).containsExactlyInAnyOrder(atFrom, mid, atTo)
        assertThat(ranged.map { it.id }).doesNotContain(old, recent)
    }

    @Test
    fun `metadata is parsed and ip or ua hashes are never surfaced`() {
        val tag = "META-${UUID.randomUUID()}"
        val id = seed(
            actorType = tag,
            action = "X",
            metadataJson = """{"region":"eu","resultCount":3}""",
            ipHash = "ip-secret-hash",
            userAgentHash = "ua-secret-hash",
        )

        val row = view.list(AuditFilters(actorType = tag), null).items.single()
        assertThat(row.id).isEqualTo(id)
        assertThat(row.metadata["region"]).isEqualTo("eu")
        assertThat(row.metadata["resultCount"]).isEqualTo(3)
        // The row type has no ip/ua field; assert the secret values leak into no string field either.
        val allText = listOfNotNull(row.actorType, row.actorId, row.action, row.entityType, row.entityId) +
            row.metadata.values.map { it.toString() }
        assertThat(allText).noneMatch { it.contains("secret-hash") }
    }

    @Test
    fun `exportCsv has the header, escaped rows and no hash columns`() {
        val tag = "CSV-${UUID.randomUUID()}"
        // A value with a comma + quote to exercise CSV escaping (entity_type here).
        seed(actorType = tag, action = "PLAIN_ACTION", entityType = """weird,"type""")

        val export = view.exportCsv(AuditFilters(actorType = tag))
        assertThat(export.truncated).isFalse()
        assertThat(export.rowCount).isEqualTo(1)
        val csv = String(export.bytes, Charsets.UTF_8)
        val lines = csv.trim().split("\n")
        assertThat(lines.first()).isEqualTo("createdAt,actorType,actorId,action,entityType,entityId")
        // No metadata/hash columns in the header.
        assertThat(lines.first()).doesNotContain("metadata", "ipHash", "ip_hash", "userAgent", "user_agent")

        // The escaped entity_type field is quoted with the inner quote doubled.
        assertThat(csv).contains(""""weird,""type"""")
        assertThat(csv).contains("PLAIN_ACTION")
        // No leaked hashes anywhere.
        assertThat(csv).doesNotContain("ip-secret", "ua-secret")
    }
}
