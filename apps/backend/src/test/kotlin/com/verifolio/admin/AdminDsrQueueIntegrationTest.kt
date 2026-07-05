package com.verifolio.admin

import com.verifolio.admin.application.AdminSessions
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.platform.VerifolioProperties
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
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
 * Task 4 admin DSR review queue: RBAC gating (L1 read-only vs L2/SUPERADMIN decide/execute),
 * audited reads (ADMIN_DSR_VIEWED), region scoping, the approve/reject/execute transitions with the
 * ADMIN actor recorded on the audit, execute-success on an automated recommender-DELETION, and the
 * 409 EXECUTION_NOT_AUTOMATED for a not-yet-automated owner EXPORT. Admins/sessions are inserted
 * directly (role fixtures) so each test's RBAC state is deterministic; DSRs are seeded per-test in a
 * unique region so counts/lists are isolated from other classes sharing the JVM/DB.
 */
class AdminDsrQueueIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var dsl: DSLContext
    @Autowired internal lateinit var sessions: AdminSessions
    @Autowired lateinit var props: VerifolioProperties

    // --- fixtures ------------------------------------------------------------

    /** Inserts an ACTIVE admin of [role] in [region] and mints a session; returns (cookie, adminId). */
    private fun admin(role: String, region: String): Pair<String, UUID> {
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

    /**
     * Seeds a DSR. The DSR `dsr_subject` check requires EXACTLY one of user_id / recommender_contact_id:
     * an owner-channel DSR (default) gets a real user_account; passing [recommenderContactId] makes it a
     * recommender-channel DSR instead.
     */
    private fun seedDsr(
        region: String,
        type: String,
        status: String = "RECEIVED",
        recommenderContactId: UUID? = null,
    ): UUID {
        val now = OffsetDateTime.now()
        val userId = if (recommenderContactId == null) ownerUser(region) else null
        return dsl.insertInto(DATA_SUBJECT_REQUEST)
            .set(DATA_SUBJECT_REQUEST.ID, UUID.randomUUID())
            .set(DATA_SUBJECT_REQUEST.TYPE, type)
            .set(DATA_SUBJECT_REQUEST.STATUS, status)
            .set(DATA_SUBJECT_REQUEST.REGION, region)
            .set(DATA_SUBJECT_REQUEST.SUBJECT_EMAIL, "subject-${UUID.randomUUID()}@example.com")
            .set(DATA_SUBJECT_REQUEST.RECOMMENDER_CONTACT_ID, recommenderContactId)
            .set(DATA_SUBJECT_REQUEST.USER_ID, userId)
            .set(DATA_SUBJECT_REQUEST.DUE_AT, now.plusDays(30))
            .returning(DATA_SUBJECT_REQUEST.ID).fetchOne()!!.id!!
    }

    /** A user_account in [region] (+ its person_profile). */
    private fun ownerUser(region: String): UUID {
        val userId = dsl.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.EMAIL, "owner-${UUID.randomUUID()}@example.com")
            .set(USER_ACCOUNT.REGION, region).set(USER_ACCOUNT.STATUS, "ACTIVE")
            .returning(USER_ACCOUNT.ID).fetchOne()!!.id!!
        return userId
    }

    /** A recommender_contact (owner user → person_profile → contact) for a recommender-channel DSR. */
    private fun recommenderContact(region: String): UUID {
        val ownerId = ownerUser(region)
        val profileId = dsl.insertInto(PERSON_PROFILE)
            .set(PERSON_PROFILE.USER_ACCOUNT_ID, ownerId)
            .set(PERSON_PROFILE.DISPLAY_NAME, "Owner")
            .returning(PERSON_PROFILE.ID).fetchOne()!!.id!!
        return dsl.insertInto(RECOMMENDER_CONTACT)
            .set(RECOMMENDER_CONTACT.OWNER_PROFILE_ID, profileId)
            .set(RECOMMENDER_CONTACT.NAME, "Rec Ommender")
            .set(RECOMMENDER_CONTACT.EMAIL, "rec-${UUID.randomUUID()}@corp.example.com")
            .set(RECOMMENDER_CONTACT.RELATIONSHIP_TYPE, "MANAGER")
            .returning(RECOMMENDER_CONTACT.ID).fetchOne()!!.id!!
    }

    // --- HTTP helpers --------------------------------------------------------

    private fun get(path: String, cookie: String) = rest.exchange(
        path, HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
        Map::class.java,
    )

    private fun xsrf(cookie: String): String =
        get("/api/v1/admin/me", cookie).headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("XSRF-TOKEN=") }.substringAfter("XSRF-TOKEN=").substringBefore(";")

    private fun post(path: String, body: Any?, cookie: String): org.springframework.http.ResponseEntity<Map<*, *>> {
        val token = xsrf(cookie)
        return rest.exchange(
            path, HttpMethod.POST,
            HttpEntity(
                body,
                HttpHeaders().apply {
                    add(HttpHeaders.COOKIE, cookie)
                    add(HttpHeaders.COOKIE, "XSRF-TOKEN=$token")
                    add("X-XSRF-TOKEN", token)
                    set(HttpHeaders.CONTENT_TYPE, "application/json")
                },
            ),
            Map::class.java,
        )
    }

    private fun auditCount(action: String, entityId: String): Int =
        dsl.fetchCount(
            AUDIT_EVENT,
            AUDIT_EVENT.ACTION.eq(action).and(AUDIT_EVENT.ENTITY_ID.eq(entityId)),
        )

    // --- tests ---------------------------------------------------------------

    @Test
    fun `L1 can list and read detail (audited) but cannot approve`() {
        val region = "dsrq-${UUID.randomUUID()}"
        val (l1, adminId) = admin("SUPPORT_L1", region)
        val dsrId = seedDsr(region, "EXPORT")

        val list = get("/api/v1/admin/data-subject-requests", l1)
        assertThat(list.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = list.body!!["items"] as List<Map<String, Any?>>
        assertThat(items.map { it["id"] }).contains(dsrId.toString())
        // The list read must NOT expose any letter/answer content — only DSR metadata fields.
        assertThat(items.first().keys).containsExactlyInAnyOrder(
            "id", "type", "status", "subjectEmail", "dueAt", "createdAt", "resolutionNotes",
        )

        val detail = get("/api/v1/admin/data-subject-requests/$dsrId", l1)
        assertThat(detail.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(detail.body!!["status"]).isEqualTo("RECEIVED")

        // Both reads are audited as ADMIN_DSR_VIEWED (list has no single entityId; detail carries dsrId).
        assertThat(dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTION.eq("ADMIN_DSR_VIEWED"))).isGreaterThan(0)
        assertThat(auditCount("ADMIN_DSR_VIEWED", dsrId.toString())).isEqualTo(1)
        assertThat(
            dsl.select(AUDIT_EVENT.ACTOR_TYPE).from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ACTION.eq("ADMIN_DSR_VIEWED").and(AUDIT_EVENT.ENTITY_ID.eq(dsrId.toString())))
                .fetchOne(AUDIT_EVENT.ACTOR_TYPE),
        ).isEqualTo("ADMIN")
        assertThat(
            dsl.select(AUDIT_EVENT.ACTOR_ID).from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ACTION.eq("ADMIN_DSR_VIEWED").and(AUDIT_EVENT.ENTITY_ID.eq(dsrId.toString())))
                .fetchOne(AUDIT_EVENT.ACTOR_ID),
        ).isEqualTo(adminId.toString())

        // RBAC: L1 lacks DSR_DECIDE → approve is 403 FORBIDDEN.
        val approve = post("/api/v1/admin/data-subject-requests/$dsrId/approve", null, l1)
        assertThat(approve.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(approve.body!!["code"]).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `L2 approves RECEIVED to APPROVED with the admin actor on the audit`() {
        val region = "dsrq-${UUID.randomUUID()}"
        val (l2, adminId) = admin("SUPPORT_L2", region)
        val dsrId = seedDsr(region, "EXPORT")

        val resp = post("/api/v1/admin/data-subject-requests/$dsrId/approve", null, l2)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body!!["status"]).isEqualTo("APPROVED")

        assertThat(
            dsl.select(DATA_SUBJECT_REQUEST.STATUS).from(DATA_SUBJECT_REQUEST)
                .where(DATA_SUBJECT_REQUEST.ID.eq(dsrId)).fetchOne(DATA_SUBJECT_REQUEST.STATUS),
        ).isEqualTo("APPROVED")
        // The lifecycle audit carries the ADMIN actor = this admin's id.
        assertThat(
            dsl.select(AUDIT_EVENT.ACTOR_TYPE, AUDIT_EVENT.ACTOR_ID).from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ACTION.eq("DATA_SUBJECT_REQUEST_APPROVED").and(AUDIT_EVENT.ENTITY_ID.eq(dsrId.toString())))
                .fetch().map { it.value1() to it.value2() },
        ).containsExactly("ADMIN" to adminId.toString())
    }

    @Test
    fun `SUPERADMIN rejects with notes recorded`() {
        val region = "dsrq-${UUID.randomUUID()}"
        val (root, _) = admin("SUPERADMIN", region)
        val dsrId = seedDsr(region, "CORRECTION")

        val resp = post("/api/v1/admin/data-subject-requests/$dsrId/reject", mapOf("notes" to "Not verifiable"), root)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body!!["status"]).isEqualTo("REJECTED")
        assertThat(
            dsl.select(DATA_SUBJECT_REQUEST.RESOLUTION_NOTES).from(DATA_SUBJECT_REQUEST)
                .where(DATA_SUBJECT_REQUEST.ID.eq(dsrId)).fetchOne(DATA_SUBJECT_REQUEST.RESOLUTION_NOTES),
        ).isEqualTo("Not verifiable")
    }

    @Test
    fun `execute on an automated recommender-DELETION reaches EXECUTED`() {
        val region = "dsrq-${UUID.randomUUID()}"
        val (l2, _) = admin("SUPPORT_L2", region)
        // A recommender-scoped DELETION (recommenderContactId set) is an automated type; with no
        // in-scope reference requests it still resolves cleanly to EXECUTED.
        val dsrId = seedDsr(region, "DELETION", recommenderContactId = recommenderContact(region))

        val resp = post("/api/v1/admin/data-subject-requests/$dsrId/execute", null, l2)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body!!["status"]).isEqualTo("EXECUTED")
        assertThat(
            dsl.select(DATA_SUBJECT_REQUEST.STATUS).from(DATA_SUBJECT_REQUEST)
                .where(DATA_SUBJECT_REQUEST.ID.eq(dsrId)).fetchOne(DATA_SUBJECT_REQUEST.STATUS),
        ).isEqualTo("EXECUTED")
    }

    @Test
    fun `execute on an owner EXPORT returns 409 EXECUTION_NOT_AUTOMATED`() {
        val region = "dsrq-${UUID.randomUUID()}"
        val (l2, _) = admin("SUPPORT_L2", region)
        val dsrId = seedDsr(region, "EXPORT")

        val resp = post("/api/v1/admin/data-subject-requests/$dsrId/execute", null, l2)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(resp.body!!["code"]).isEqualTo("EXECUTION_NOT_AUTOMATED")
        // Unchanged — still RECEIVED.
        assertThat(
            dsl.select(DATA_SUBJECT_REQUEST.STATUS).from(DATA_SUBJECT_REQUEST)
                .where(DATA_SUBJECT_REQUEST.ID.eq(dsrId)).fetchOne(DATA_SUBJECT_REQUEST.STATUS),
        ).isEqualTo("RECEIVED")
    }

    @Test
    fun `region scoping — a DSR in another region is not listed and 404s on detail`() {
        val region = "dsrq-${UUID.randomUUID()}"
        val otherRegion = "dsrq-other-${UUID.randomUUID()}"
        val (l2, _) = admin("SUPPORT_L2", region)
        val mine = seedDsr(region, "EXPORT")
        val foreign = seedDsr(otherRegion, "EXPORT")

        val list = get("/api/v1/admin/data-subject-requests", l2)
        @Suppress("UNCHECKED_CAST")
        val ids = (list.body!!["items"] as List<Map<String, Any?>>).map { it["id"] }
        assertThat(ids).contains(mine.toString())
        assertThat(ids).doesNotContain(foreign.toString())

        assertThat(get("/api/v1/admin/data-subject-requests/$foreign", l2).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
        // A decision on a foreign-region DSR is also a 404 (region gate before transition).
        assertThat(post("/api/v1/admin/data-subject-requests/$foreign/approve", null, l2).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `dashboard counts reflect seeded statuses`() {
        val region = "dsrq-${UUID.randomUUID()}"
        val (l1, _) = admin("SUPPORT_L1", region)
        seedDsr(region, "EXPORT", status = "RECEIVED")
        seedDsr(region, "EXPORT", status = "RECEIVED")
        seedDsr(region, "CORRECTION", status = "APPROVED")
        seedDsr(region, "DELETION", status = "EXECUTED")

        val resp = get("/api/v1/admin/dashboard", l1)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val byStatus = resp.body!!["dsrByStatus"] as Map<String, Int>
        assertThat(byStatus["RECEIVED"]).isEqualTo(2)
        assertThat(byStatus["APPROVED"]).isEqualTo(1)
        assertThat(byStatus["EXECUTED"]).isEqualTo(1)
        // Pending = non-terminal (RECEIVED + APPROVED); EXECUTED is terminal.
        assertThat(resp.body!!["dsrPendingTotal"]).isEqualTo(3)
    }

    @Test
    fun `an unauthenticated request is 401`() {
        val resp = rest.getForEntity("/api/v1/admin/dashboard", Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
