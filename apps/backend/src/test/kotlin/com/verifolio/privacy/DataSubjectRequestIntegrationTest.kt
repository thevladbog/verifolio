package com.verifolio.privacy

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime
import java.util.UUID

/** Account-holder DSR channel: submit + list + SLA + audit. */
class DataSubjectRequestIntegrationTest : PrivacyFlowSupport() {

    @Test
    fun `owner submits a DELETION request which is RECEIVED, due in 30 days, audited, and listed`() {
        val owner = "dsr_owner@example.com"
        val cookie = login(owner)
        val csrf = xsrf(cookie)

        val submit = rest.exchange(
            "/api/v1/privacy/data-subject-requests", HttpMethod.POST,
            HttpEntity(mapOf("type" to "DELETION", "comment" to "please erase"), authHeaders(cookie, csrf)),
            Map::class.java,
        )
        assertThat(submit.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = submit.body!!
        val dsrId = body["id"] as String
        assertThat(body["type"]).isEqualTo("DELETION")
        assertThat(body["status"]).isEqualTo("RECEIVED")
        assertThat(body["subjectEmail"]).isEqualTo(owner)
        assertThat(body["verifiedAt"]).isNotNull()

        // due_at ≈ now + 30d (generous window for clock skew and container time).
        val dueAt = OffsetDateTime.parse(body["dueAt"] as String)
        assertThat(dueAt).isAfter(OffsetDateTime.now().plusDays(29))
        assertThat(dueAt).isBefore(OffsetDateTime.now().plusDays(31))

        // Persisted RECEIVED with the owner as subject.
        val dsr = DATA_SUBJECT_REQUEST
        val row = dsl.selectFrom(dsr).where(dsr.ID.eq(UUID.fromString(dsrId))).fetchOne()!!
        assertThat(row.status).isEqualTo("RECEIVED")
        assertThat(row.userId).isNotNull()
        assertThat(row.recommenderContactId).isNull()

        // Audit RECEIVED by USER.
        val audited = dsl.fetchCount(
            AUDIT_EVENT,
            AUDIT_EVENT.ACTION.eq("DATA_SUBJECT_REQUEST_RECEIVED")
                .and(AUDIT_EVENT.ENTITY_ID.eq(dsrId))
                .and(AUDIT_EVENT.ACTOR_TYPE.eq("USER")),
        )
        assertThat(audited).isEqualTo(1)

        // List returns it.
        val list = rest.exchange(
            "/api/v1/privacy/data-subject-requests", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(list.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val listed = list.body!!["items"] as List<Map<String, Any>>
        assertThat(listed.map { it["id"] }).contains(dsrId)
    }

    @Test
    fun `owner CONSENT_WITHDRAWAL is rejected — consent withdrawal is a recommender-only right`() {
        val cookie = login("dsr_cw_owner@example.com")
        val csrf = xsrf(cookie)

        val submit = rest.exchange(
            "/api/v1/privacy/data-subject-requests", HttpMethod.POST,
            HttpEntity(mapOf("type" to "CONSENT_WITHDRAWAL"), authHeaders(cookie, csrf)),
            Map::class.java,
        )
        assertThat(submit.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(submit.body!!["code"]).isEqualTo("CONSENT_WITHDRAWAL_NOT_APPLICABLE")

        // No DSR row was created.
        assertThat(
            dsl.fetchCount(
                DATA_SUBJECT_REQUEST,
                DATA_SUBJECT_REQUEST.SUBJECT_EMAIL.eq("dsr_cw_owner@example.com"),
            ),
        ).isZero()
    }

    @Test
    fun `listing DSRs requires authentication`() {
        // GET carries no CSRF, so an anonymous call resolves to the authenticated matcher (401).
        val response = rest.getForEntity("/api/v1/privacy/data-subject-requests", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
