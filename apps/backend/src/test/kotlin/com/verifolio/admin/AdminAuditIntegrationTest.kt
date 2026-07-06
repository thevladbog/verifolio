package com.verifolio.admin

import com.verifolio.admin.application.AdminSessions
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Task 4 admin audit-log viewer: RBAC gating (L1 lacks AUDIT_VIEW → 403; L2 has list but not
 * AUDIT_EXPORT → export 403; SUPERADMIN exports), audited reads (ADMIN_AUDIT_LOG_VIEWED — itself
 * an audit-of-audit row), CSV shape (header + no ip/ua hashes), filters, and the date-parse 400.
 * Admins/sessions are inserted directly for deterministic RBAC.
 */
class AdminAuditIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var dsl: DSLContext
    @Autowired internal lateinit var sessions: AdminSessions

    /** Inserts an ACTIVE admin of [role] and mints a session; returns (cookie, adminId). */
    private fun admin(role: String): Pair<String, UUID> {
        val region = "aud-${UUID.randomUUID()}"
        val email = "admin-${UUID.randomUUID()}@verifolio.local"
        val userId = dsl.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.EMAIL, email).set(USER_ACCOUNT.REGION, region).set(USER_ACCOUNT.STATUS, "ACTIVE")
            .returning(USER_ACCOUNT.ID).fetchOne()!!.id!!
        val adminId = dsl.insertInto(ADMIN_ACCOUNT)
            .set(ADMIN_ACCOUNT.USER_ACCOUNT_ID, userId)
            .set(ADMIN_ACCOUNT.EMAIL, email)
            .set(ADMIN_ACCOUNT.REGION, region)
            .set(ADMIN_ACCOUNT.ROLE, role)
            .set(ADMIN_ACCOUNT.STATUS, "ACTIVE")
            .returning(ADMIN_ACCOUNT.ID).fetchOne()!!.id!!
        val session = sessions.mint(adminId, null, null)
        return "verifolio_admin_session=${session.rawToken}" to adminId
    }

    private fun seedAudit(actorType: String, action: String, entityType: String = "SOME_ENTITY"): UUID {
        val ae = AUDIT_EVENT
        return dsl.insertInto(ae)
            .set(ae.ID, UUID.randomUUID())
            .set(ae.ACTOR_TYPE, actorType)
            .set(ae.ACTOR_ID, UUID.randomUUID().toString())
            .set(ae.ACTION, action)
            .set(ae.ENTITY_TYPE, entityType)
            .set(ae.ENTITY_ID, UUID.randomUUID().toString())
            .set(ae.METADATA, JSONB.jsonb("""{"region":"eu"}"""))
            .set(ae.IP_HASH, "ip-secret-hash")
            .set(ae.USER_AGENT_HASH, "ua-secret-hash")
            .set(ae.CREATED_AT, OffsetDateTime.now())
            .returning(ae.ID).fetchOne()!!.id!!
    }

    private fun entity(cookie: String) =
        HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) })

    private fun getJson(path: String, cookie: String) =
        rest.exchange(path, HttpMethod.GET, entity(cookie), Map::class.java)

    private fun getText(path: String, cookie: String) =
        rest.exchange(path, HttpMethod.GET, entity(cookie), String::class.java)

    @Test
    fun `L1 lacks AUDIT_VIEW so the list is 403`() {
        val (l1, _) = admin("SUPPORT_L1")
        val resp = getJson("/api/v1/admin/audit-logs", l1)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(resp.body!!["code"]).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `L2 lists with filters and the read is audited`() {
        val (l2, adminId) = admin("SUPPORT_L2")
        val tag = "L2LIST-${UUID.randomUUID()}"
        seedAudit(actorType = tag, action = "SEEDED_ACTION")

        val resp = getJson("/api/v1/admin/audit-logs?actorType=$tag", l2)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = resp.body!!["items"] as List<Map<String, Any?>>
        assertThat(items).isNotEmpty
        assertThat(items).allMatch { it["actorType"] == tag }
        // Rows carry no ip/ua hash keys.
        assertThat(items.first().keys).containsExactlyInAnyOrder(
            "id", "createdAt", "actorType", "actorId", "action", "entityType", "entityId", "metadata",
        )

        // The list read is itself audited (ADMIN_AUDIT_LOG_VIEWED, actor ADMIN = this admin).
        assertThat(
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ACTION.eq("ADMIN_AUDIT_LOG_VIEWED").and(AUDIT_EVENT.ACTOR_ID.eq(adminId.toString())),
            ),
        ).isGreaterThan(0)
    }

    @Test
    fun `L2 cannot export (needs AUDIT_EXPORT)`() {
        val (l2, _) = admin("SUPPORT_L2")
        val resp = getJson("/api/v1/admin/audit-logs/export", l2)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(resp.body!!["code"]).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `SUPERADMIN exports a CSV attachment with the header and no hashes`() {
        val (su, adminId) = admin("SUPERADMIN")
        val tag = "EXPORT-${UUID.randomUUID()}"
        seedAudit(actorType = tag, action = "EXPORTABLE_ACTION")

        val resp = getText("/api/v1/admin/audit-logs/export?actorType=$tag", su)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.headers.contentType.toString()).startsWith("text/csv")
        assertThat(resp.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment")

        val csv = resp.body!!
        assertThat(csv.lineSequence().first()).isEqualTo("createdAt,actorType,actorId,action,entityType,entityId")
        assertThat(csv).contains("EXPORTABLE_ACTION")
        assertThat(csv).doesNotContain("ip-secret-hash", "ua-secret-hash")

        // Export reads are audited too.
        assertThat(
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ACTION.eq("ADMIN_AUDIT_LOG_VIEWED").and(AUDIT_EVENT.ACTOR_ID.eq(adminId.toString())),
            ),
        ).isGreaterThan(0)
    }

    @Test
    fun `a malformed from is a 400`() {
        val (l2, _) = admin("SUPPORT_L2")
        val resp = getJson("/api/v1/admin/audit-logs?from=not-a-date", l2)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(resp.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `a malformed cursor is a 400 not a 500`() {
        val (l2, _) = admin("SUPPORT_L2")
        val resp = getJson("/api/v1/admin/audit-logs?cursor=not-a-valid-cursor", l2)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(resp.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `an unauthenticated request is 401`() {
        val resp = rest.getForEntity("/api/v1/admin/audit-logs", Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
