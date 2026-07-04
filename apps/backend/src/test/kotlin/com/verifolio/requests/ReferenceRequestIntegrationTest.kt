package com.verifolio.requests

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.INVITATION_TOKEN
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

@Import(RecordingMailConfig::class)
class ReferenceRequestIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    /** POST magic-link, extract token, POST session, return the session cookie value. */
    private fun login(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        return response.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("verifolio_session=") }.substringBefore(";")
    }

    /** GET an API page to obtain the XSRF token from the Set-Cookie header. */
    private fun xsrf(cookie: String): String? {
        val response = rest.exchange(
            "/api/v1/reference-requests", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        return response.headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
    }

    private fun authHeaders(cookie: String, xsrfToken: String?): HttpHeaders = HttpHeaders().apply {
        add(HttpHeaders.COOKIE, cookie)
        set(HttpHeaders.CONTENT_TYPE, "application/json")
        if (xsrfToken != null) {
            add(HttpHeaders.COOKIE, "XSRF-TOKEN=$xsrfToken")
            add("X-XSRF-TOKEN", xsrfToken)
        }
    }

    private fun createContact(cookie: String, xsrfToken: String?, email: String = "rec@corp.example.com"): String {
        val response = rest.exchange(
            "/api/v1/contacts", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "name" to "Rec Ommender",
                    "email" to email,
                    "relationshipType" to "MANAGER",
                ),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!["id"] as String
    }

    private fun anyTemplateId(cookie: String): String {
        val response = rest.exchange(
            "/api/v1/templates?locale=en", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val items = response.body!!["items"] as List<Map<String, Any>>
        return items.first()["id"] as String
    }

    private fun createRequest(
        cookie: String,
        xsrfToken: String?,
        contactId: String,
        templateId: String,
        attested: Boolean = true,
        purpose: String? = "Visa application",
    ) = rest.exchange(
        "/api/v1/reference-requests", HttpMethod.POST,
        HttpEntity(
            mapOf(
                "recommenderContactId" to contactId,
                "templateId" to templateId,
                "purpose" to purpose,
                "verbalConsentAttested" to attested,
            ),
            authHeaders(cookie, xsrfToken),
        ),
        Map::class.java,
    )

    private fun auditActions(): List<String?> =
        dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)

    // ---- create ----

    @Test
    fun `create records request, consent record and audit events`() {
        val cookie = login("req_creator@example.com")
        val xsrfToken = xsrf(cookie)
        val contactId = createContact(cookie, xsrfToken)
        val templateId = anyTemplateId(cookie)

        val response = createRequest(cookie, xsrfToken, contactId, templateId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["status"]).isEqualTo("CREATED")
        assertThat(body["purpose"]).isEqualTo("Visa application")
        assertThat(body["expiresAt"]).isNotNull()
        val requestId = UUID.fromString(body["id"] as String)

        val cr = CONSENT_RECORD
        val consent = dsl.selectFrom(cr).where(cr.REFERENCE_REQUEST_ID.eq(requestId)).fetchOne()!!
        assertThat(consent.subjectType).isEqualTo("REQUESTER")
        assertThat(consent.userId).isNotNull()
        assertThat(consent.recommenderContactId).isNull()
        assertThat(consent.consentType).isEqualTo("REQUESTER_VERBAL_CONSENT_ATTESTATION")
        assertThat(consent.policyTextVersion).isEqualTo("local-requester-attestation:1")
        assertThat(consent.region).isEqualTo("local")
        assertThat(consent.status).isEqualTo("GRANTED")
        assertThat(consent.grantedAt).isNotNull()

        assertThat(auditActions()).contains("REFERENCE_REQUEST_CREATED", "CONSENT_GRANTED")
    }

    @Test
    fun `create without attestation is rejected and stores nothing`() {
        val cookie = login("req_no_consent@example.com")
        val xsrfToken = xsrf(cookie)
        val contactId = createContact(cookie, xsrfToken)
        val templateId = anyTemplateId(cookie)

        val response = createRequest(cookie, xsrfToken, contactId, templateId, attested = false)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("CONSENT_REQUIRED")

        val rr = REFERENCE_REQUEST
        val count = dsl.fetchCount(
            dsl.selectFrom(rr).where(rr.RECOMMENDER_CONTACT_ID.eq(UUID.fromString(contactId))),
        )
        assertThat(count).isZero()
    }

    @Test
    fun `create with another users contact returns 404`() {
        val ownerCookie = login("contact_owner_a@example.com")
        val ownerXsrf = xsrf(ownerCookie)
        val foreignContactId = createContact(ownerCookie, ownerXsrf, email = "foreign@corp.example.com")

        val cookie = login("intruder_b@example.com")
        val xsrfToken = xsrf(cookie)
        val templateId = anyTemplateId(cookie)

        val response = createRequest(cookie, xsrfToken, foreignContactId, templateId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `create with unknown template returns 400`() {
        val cookie = login("req_bad_template@example.com")
        val xsrfToken = xsrf(cookie)
        val contactId = createContact(cookie, xsrfToken)

        val response = createRequest(cookie, xsrfToken, contactId, UUID.randomUUID().toString())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }

    // ---- get / list ----

    @Test
    fun `get returns 404 for another users request`() {
        val cookieA = login("owner_get_a@example.com")
        val xsrfA = xsrf(cookieA)
        val contactId = createContact(cookieA, xsrfA)
        val templateId = anyTemplateId(cookieA)
        val requestId = createRequest(cookieA, xsrfA, contactId, templateId).body!!["id"] as String

        val cookieB = login("other_get_b@example.com")
        val response = rest.exchange(
            "/api/v1/reference-requests/$requestId", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookieB) }),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `list is owner scoped and supports status filter`() {
        val cookie = login("list_owner@example.com")
        val xsrfToken = xsrf(cookie)
        val contactId = createContact(cookie, xsrfToken)
        val templateId = anyTemplateId(cookie)
        createRequest(cookie, xsrfToken, contactId, templateId)
        createRequest(cookie, xsrfToken, contactId, templateId)

        val listResponse = rest.exchange(
            "/api/v1/reference-requests?status=CREATED", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(listResponse.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = listResponse.body!!["items"] as List<Map<String, Any>>
        assertThat(items).hasSize(2)
        assertThat(items).allSatisfy { assertThat(it["status"]).isEqualTo("CREATED") }

        val otherCookie = login("list_other@example.com")
        val otherResponse = rest.exchange(
            "/api/v1/reference-requests", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, otherCookie) }),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val otherItems = otherResponse.body!!["items"] as List<Map<String, Any>>
        assertThat(otherItems).isEmpty()
    }

    // ---- send ----

    private fun send(cookie: String, xsrfToken: String?, requestId: String) = rest.exchange(
        "/api/v1/reference-requests/$requestId/send", HttpMethod.POST,
        HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
        Map::class.java,
    )

    @Test
    fun `send mails a tokenized invitation and moves the request to SENT`() {
        val cookie = login("sender_happy@example.com")
        val xsrfToken = xsrf(cookie)
        val contactId = createContact(cookie, xsrfToken, email = "happy_rec@corp.example.com")
        val templateId = anyTemplateId(cookie)
        val requestId = createRequest(cookie, xsrfToken, contactId, templateId).body!!["id"] as String

        val response = send(cookie, xsrfToken, requestId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["status"]).isEqualTo("SENT")

        val invitation = mail.sent.last { it.to == "happy_rec@corp.example.com" }
        assertThat(invitation.textBody).contains("/invitations/")
        val rawToken = Regex("/invitations/([A-Za-z0-9_-]+)")
            .find(invitation.textBody)!!.groupValues[1]

        val it = INVITATION_TOKEN
        val tokenRow = dsl.selectFrom(it)
            .where(it.REQUEST_ID.eq(UUID.fromString(requestId)))
            .fetchOne()!!
        // Raw token never stored — only the HMAC hash.
        assertThat(tokenRow.tokenHash).isNotEqualTo(rawToken)
        assertThat(tokenRow.recommenderEmail).isEqualTo("happy_rec@corp.example.com")
        assertThat(tokenRow.consumedAt).isNull()
        assertThat(tokenRow.revokedAt).isNull()

        assertThat(auditActions()).contains("REFERENCE_REQUEST_SENT")
    }

    @Test
    fun `send twice returns 409`() {
        val cookie = login("sender_twice@example.com")
        val xsrfToken = xsrf(cookie)
        val contactId = createContact(cookie, xsrfToken, email = "twice_rec@corp.example.com")
        val templateId = anyTemplateId(cookie)
        val requestId = createRequest(cookie, xsrfToken, contactId, templateId).body!!["id"] as String

        assertThat(send(cookie, xsrfToken, requestId).statusCode).isEqualTo(HttpStatus.OK)

        val second = send(cookie, xsrfToken, requestId)
        assertThat(second.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(second.body!!["code"]).isEqualTo("INVALID_REQUEST_STATE")
    }

    @Test
    fun `send is rate limited per recommender email`() {
        val cookie = login("sender_limited@example.com")
        val xsrfToken = xsrf(cookie)
        val contactId = createContact(cookie, xsrfToken, email = "limited_rec@corp.example.com")
        val templateId = anyTemplateId(cookie)

        repeat(5) {
            val requestId = createRequest(cookie, xsrfToken, contactId, templateId).body!!["id"] as String
            assertThat(send(cookie, xsrfToken, requestId).statusCode).isEqualTo(HttpStatus.OK)
        }

        val requestId = createRequest(cookie, xsrfToken, contactId, templateId).body!!["id"] as String
        val response = send(cookie, xsrfToken, requestId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(response.body!!["code"]).isEqualTo("RATE_LIMITED")
    }

    @Test
    fun `send of another users request returns 404`() {
        val cookieA = login("send_owner_a@example.com")
        val xsrfA = xsrf(cookieA)
        val contactId = createContact(cookieA, xsrfA, email = "send404_rec@corp.example.com")
        val templateId = anyTemplateId(cookieA)
        val requestId = createRequest(cookieA, xsrfA, contactId, templateId).body!!["id"] as String

        val cookieB = login("send_other_b@example.com")
        val xsrfB = xsrf(cookieB)

        assertThat(send(cookieB, xsrfB, requestId).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `list with garbage status filter returns 400`() {
        val cookie = login("list_bad_status@example.com")

        val response = rest.exchange(
            "/api/v1/reference-requests?status=NOT_A_STATUS", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }
}
