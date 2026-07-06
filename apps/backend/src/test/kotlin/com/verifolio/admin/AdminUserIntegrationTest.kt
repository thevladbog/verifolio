package com.verifolio.admin

import com.verifolio.admin.application.AdminSessions
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.jooq.tables.references.USER_SESSION
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
 * Task 3 admin user list + card: RBAC gating (L1 lacks USER_VIEW → 403; L2 has it), region scoping
 * (foreign-region user → 404), audited reads (ADMIN_USER_LIST_VIEWED / ADMIN_USER_DETAIL_VIEWED,
 * IDs/counts only), the composed card (account/profile/document counts/consents/sessions/DSR counts)
 * with NO document content, and 401 for the unauthenticated case. Admins/sessions and the seeded
 * account holder are inserted directly (deterministic RBAC + isolated per-test region).
 */
class AdminUserIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var dsl: DSLContext
    @Autowired internal lateinit var sessions: AdminSessions

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
     * Seeds a full account holder in [region]: user_account + person_profile + one document with a
     * LOCKED version + one consent + two sessions + one DSR. Returns (userId, displayName, email).
     */
    private data class Holder(val userId: UUID, val displayName: String, val email: String)

    private fun seedHolder(region: String): Holder {
        val tag = UUID.randomUUID().toString().take(8)
        val email = "holder-$tag@example.com"
        val displayName = "Zephyrine $tag"
        val userId = dsl.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.EMAIL, email).set(USER_ACCOUNT.REGION, region).set(USER_ACCOUNT.STATUS, "ACTIVE")
            .returning(USER_ACCOUNT.ID).fetchOne()!!.id!!
        val profileId = dsl.insertInto(PERSON_PROFILE)
            .set(PERSON_PROFILE.USER_ACCOUNT_ID, userId)
            .set(PERSON_PROFILE.DISPLAY_NAME, displayName)
            .set(PERSON_PROFILE.LEGAL_NAME, "Zephyrine Legal")
            .set(PERSON_PROFILE.PREFERRED_LOCALE, "en")
            .returning(PERSON_PROFILE.ID).fetchOne()!!.id!!

        val documentId = dsl.insertInto(DOCUMENT)
            .set(DOCUMENT.ID, UUID.randomUUID())
            .set(DOCUMENT.OWNER_PROFILE_ID, profileId)
            .set(DOCUMENT.TYPE, "REFERENCE_LETTER")
            .returning(DOCUMENT.ID).fetchOne()!!.id!!
        dsl.insertInto(DOCUMENT_VERSION)
            .set(DOCUMENT_VERSION.ID, UUID.randomUUID())
            .set(DOCUMENT_VERSION.DOCUMENT_ID, documentId)
            .set(DOCUMENT_VERSION.VERSION_NUMBER, 1)
            .set(DOCUMENT_VERSION.SHA256_HASH, "sha-$tag")
            .set(DOCUMENT_VERSION.STATUS, "LOCKED")
            .set(DOCUMENT_VERSION.LOCKED_AT, OffsetDateTime.now())
            .execute()

        val now = OffsetDateTime.now()
        dsl.insertInto(CONSENT_RECORD)
            .set(CONSENT_RECORD.ID, UUID.randomUUID())
            .set(CONSENT_RECORD.SUBJECT_TYPE, "REQUESTER")
            .set(CONSENT_RECORD.USER_ID, userId)
            .set(CONSENT_RECORD.CONSENT_TYPE, "REQUESTER_VERBAL_CONSENT_ATTESTATION")
            .set(CONSENT_RECORD.POLICY_TEXT_VERSION, "v1")
            .set(CONSENT_RECORD.REGION, region)
            .set(CONSENT_RECORD.STATUS, "GRANTED")
            .set(CONSENT_RECORD.GRANTED_AT, now)
            .set(CONSENT_RECORD.CREATED_AT, now)
            .execute()

        seedSession(userId, now.plusSeconds(1))
        seedSession(userId, now.plusSeconds(2))

        dsl.insertInto(DATA_SUBJECT_REQUEST)
            .set(DATA_SUBJECT_REQUEST.ID, UUID.randomUUID())
            .set(DATA_SUBJECT_REQUEST.TYPE, "EXPORT")
            .set(DATA_SUBJECT_REQUEST.STATUS, "RECEIVED")
            .set(DATA_SUBJECT_REQUEST.REGION, region)
            .set(DATA_SUBJECT_REQUEST.SUBJECT_EMAIL, email)
            .set(DATA_SUBJECT_REQUEST.USER_ID, userId)
            .set(DATA_SUBJECT_REQUEST.DUE_AT, now.plusDays(30))
            .execute()

        return Holder(userId, displayName, email)
    }

    private fun seedSession(userId: UUID, createdAt: OffsetDateTime) {
        dsl.insertInto(USER_SESSION)
            .set(USER_SESSION.ID, UUID.randomUUID())
            .set(USER_SESSION.USER_ACCOUNT_ID, userId)
            .set(USER_SESSION.TOKEN_HASH, "hash-${UUID.randomUUID()}")
            .set(USER_SESSION.IP_HASH, "ip-hash-${UUID.randomUUID()}")
            .set(USER_SESSION.USER_AGENT_HASH, "ua-hash-${UUID.randomUUID()}")
            .set(USER_SESSION.EXPIRES_AT, createdAt.plusDays(30))
            .set(USER_SESSION.CREATED_AT, createdAt)
            .execute()
    }

    // --- HTTP helper ---------------------------------------------------------

    private fun get(path: String, cookie: String) = rest.exchange(
        path, HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
        Map::class.java,
    )

    private fun auditCount(action: String, entityId: String): Int =
        dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTION.eq(action).and(AUDIT_EVENT.ENTITY_ID.eq(entityId)))

    // --- tests ---------------------------------------------------------------

    @Test
    fun `L1 lacks USER_VIEW so list and card are 403`() {
        val region = "usr-${UUID.randomUUID()}"
        val (l1, _) = admin("SUPPORT_L1", region)
        val holder = seedHolder(region)

        val list = get("/api/v1/admin/users", l1)
        assertThat(list.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(list.body!!["code"]).isEqualTo("FORBIDDEN")

        val card = get("/api/v1/admin/users/${holder.userId}", l1)
        assertThat(card.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(card.body!!["code"]).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `L2 lists region users with search and status filter, audited`() {
        val region = "usr-${UUID.randomUUID()}"
        val (l2, adminId) = admin("SUPPORT_L2", region)
        val holder = seedHolder(region)

        // Unfiltered list contains the seeded user.
        val all = get("/api/v1/admin/users", l2)
        assertThat(all.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = all.body!!["items"] as List<Map<String, Any?>>
        assertThat(items.map { it["id"] }).contains(holder.userId.toString())
        assertThat(items.first().keys).containsExactlyInAnyOrder(
            "id", "email", "displayName", "region", "status", "createdAt",
        )

        // Search by display-name prefix.
        val byName = get("/api/v1/admin/users?query=Zephyr", l2)
        @Suppress("UNCHECKED_CAST")
        val nameIds = (byName.body!!["items"] as List<Map<String, Any?>>).map { it["id"] }
        assertThat(nameIds).contains(holder.userId.toString())

        // Status filter (ACTIVE matches; DISABLED excludes the seeded ACTIVE holder).
        val disabled = get("/api/v1/admin/users?status=DISABLED", l2)
        @Suppress("UNCHECKED_CAST")
        val disabledIds = (disabled.body!!["items"] as List<Map<String, Any?>>).map { it["id"] }
        assertThat(disabledIds).doesNotContain(holder.userId.toString())

        // List reads are audited (actor ADMIN = this admin), and no email is copied into metadata.
        assertThat(dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTION.eq("ADMIN_USER_LIST_VIEWED"))).isGreaterThan(0)
        assertThat(
            dsl.select(AUDIT_EVENT.ACTOR_TYPE, AUDIT_EVENT.ACTOR_ID).from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ACTION.eq("ADMIN_USER_LIST_VIEWED").and(AUDIT_EVENT.ACTOR_ID.eq(adminId.toString())))
                .fetch().map { it.value1() }.distinct(),
        ).containsExactly("ADMIN")
    }

    @Test
    fun `L2 reads the composed card with counts and no document content, audited`() {
        val region = "usr-${UUID.randomUUID()}"
        val (l2, _) = admin("SUPPORT_L2", region)
        val holder = seedHolder(region)

        val resp = get("/api/v1/admin/users/${holder.userId}", l2)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val body = resp.body!!

        @Suppress("UNCHECKED_CAST")
        val account = body["account"] as Map<String, Any?>
        assertThat(account["email"]).isEqualTo(holder.email)
        assertThat(account["region"]).isEqualTo(region)
        assertThat(account["status"]).isEqualTo("ACTIVE")

        @Suppress("UNCHECKED_CAST")
        val profile = body["profile"] as Map<String, Any?>
        assertThat(profile["displayName"]).isEqualTo(holder.displayName)
        assertThat(profile["preferredLocale"]).isEqualTo("en")

        assertThat((body["documentCount"] as Number).toInt()).isGreaterThanOrEqualTo(1)
        assertThat((body["lockedDocumentCount"] as Number).toInt()).isGreaterThanOrEqualTo(1)
        assertThat((body["consentCount"] as Number).toInt()).isGreaterThanOrEqualTo(1)
        assertThat((body["sessionCount"] as Number).toInt()).isEqualTo(2)

        @Suppress("UNCHECKED_CAST")
        val consents = body["consents"] as List<Map<String, Any?>>
        assertThat(consents.map { it["status"] }).contains("GRANTED")

        @Suppress("UNCHECKED_CAST")
        val cardSessions = body["sessions"] as List<Map<String, Any?>>
        assertThat(cardSessions).hasSize(2)
        // Sessions expose timestamps only — never ip/user-agent hashes.
        assertThat(cardSessions.first().keys).containsExactlyInAnyOrder(
            "createdAt", "lastSeenAt", "expiresAt", "revokedAt",
        )

        @Suppress("UNCHECKED_CAST")
        val dsrCounts = body["dsrCountsByStatus"] as Map<String, Any?>
        assertThat((dsrCounts["RECEIVED"] as Number).toInt()).isEqualTo(1)

        // Support-without-content: no document/letter/file content anywhere in the card.
        assertThat(body.keys).containsExactlyInAnyOrder(
            "account", "profile", "documentCount", "lockedDocumentCount", "consentCount",
            "sessionCount", "consents", "sessions", "dsrCountsByStatus",
        )
        assertThat(body.keys).doesNotContain("documents", "contentJson", "renderedHtml", "content")

        // The detail read is audited against the user account id.
        assertThat(auditCount("ADMIN_USER_DETAIL_VIEWED", holder.userId.toString())).isEqualTo(1)
        assertThat(
            dsl.select(AUDIT_EVENT.ACTOR_TYPE).from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ACTION.eq("ADMIN_USER_DETAIL_VIEWED").and(AUDIT_EVENT.ENTITY_ID.eq(holder.userId.toString())))
                .fetchOne(AUDIT_EVENT.ACTOR_TYPE),
        ).isEqualTo("ADMIN")
    }

    @Test
    fun `a user in another region is 404 from a region R admin`() {
        val region = "usr-${UUID.randomUUID()}"
        val otherRegion = "usr-other-${UUID.randomUUID()}"
        val (l2, _) = admin("SUPPORT_L2", region)
        val foreign = seedHolder(otherRegion)

        assertThat(get("/api/v1/admin/users/${foreign.userId}", l2).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a malformed cursor is a 400 not a 500`() {
        val region = "usr-${UUID.randomUUID()}"
        val (l2, _) = admin("SUPPORT_L2", region)
        val resp = get("/api/v1/admin/users?cursor=not-a-valid-cursor", l2)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(resp.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `an unauthenticated request is 401`() {
        val resp = rest.getForEntity("/api/v1/admin/users", Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
