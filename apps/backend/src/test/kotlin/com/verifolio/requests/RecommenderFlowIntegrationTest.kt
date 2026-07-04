package com.verifolio.requests

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.INVITATION_TOKEN
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
class RecommenderFlowIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    // ---- requester-side helpers (same patterns as ReferenceRequestIntegrationTest) ----

    private fun login(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        return response.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("verifolio_session=") }.substringBefore(";")
    }

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

    /**
     * Requester creates contact + request and sends it; returns the raw invitation token
     * extracted from the invitation email, plus the request id.
     */
    private fun sendInvitation(requesterEmail: String, recommenderEmail: String): Pair<String, String> {
        val cookie = login(requesterEmail)
        val xsrfToken = xsrf(cookie)

        val contactResponse = rest.exchange(
            "/api/v1/contacts", HttpMethod.POST,
            HttpEntity(
                mapOf("name" to "Rec Ommender", "email" to recommenderEmail, "relationshipType" to "MANAGER"),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        val contactId = contactResponse.body!!["id"] as String

        val templatesResponse = rest.exchange(
            "/api/v1/templates?locale=en", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val templateId = (templatesResponse.body!!["items"] as List<Map<String, Any>>).first()["id"] as String

        val createResponse = rest.exchange(
            "/api/v1/reference-requests", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "recommenderContactId" to contactId,
                    "templateId" to templateId,
                    "purpose" to "Visa support",
                    "verbalConsentAttested" to true,
                ),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        val requestId = createResponse.body!!["id"] as String

        val sendResponse = rest.exchange(
            "/api/v1/reference-requests/$requestId/send", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(sendResponse.statusCode).isEqualTo(HttpStatus.OK)

        val invitationEmail = mail.sent.last { it.to == recommenderEmail }
        val rawToken = Regex("/invitations/([A-Za-z0-9_-]+)")
            .find(invitationEmail.textBody)!!.groupValues[1]
        return rawToken to requestId
    }

    private fun requestStatus(requestId: String): String {
        val rr = com.verifolio.jooq.tables.references.REFERENCE_REQUEST
        return dsl.select(rr.STATUS).from(rr).where(rr.ID.eq(UUID.fromString(requestId))).fetchOne(rr.STATUS)!!
    }

    private fun auditActions(): List<String?> =
        dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)

    // ---- recommender-side helpers ----

    private fun openInvitation(rawToken: String) = rest.exchange(
        "/api/v1/invitations/$rawToken", HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders()),
        Map::class.java,
    )

    private fun requestCode(rawToken: String) = rest.exchange(
        "/api/v1/invitations/$rawToken/email-confirmations", HttpMethod.POST,
        HttpEntity<Void>(HttpHeaders()),
        Map::class.java,
    )

    /** Requests a code, reads it from the mailbox, confirms, returns the recommender session cookie. */
    private fun confirmEmail(rawToken: String, recommenderEmail: String): String {
        assertThat(requestCode(rawToken).statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val code = Regex("Code: (\\d{6})")
            .find(mail.sent.last { it.to == recommenderEmail }.textBody)!!.groupValues[1]
        val response = rest.exchange(
            "/api/v1/invitations/$rawToken/confirm-email", HttpMethod.POST,
            HttpEntity(mapOf("code" to code), HttpHeaders().apply { set(HttpHeaders.CONTENT_TYPE, "application/json") }),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("verifolio_recommender_session=") }.substringBefore(";")
    }

    // ---- open ----

    @Test
    fun `opening the invitation flips SENT to OPENED once and returns the preview`() {
        val (rawToken, requestId) = sendInvitation("open_requester@example.com", "open_rec@corp.example.com")

        val first = openInvitation(rawToken)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(first.body!!["status"]).isEqualTo("OPENED")
        assertThat(first.body!!["purpose"]).isEqualTo("Visa support")
        assertThat(first.body!!["recommenderEmailMasked"]).isEqualTo("o***@corp.example.com")
        assertThat(first.body!!["requesterName"]).isNotNull()
        assertThat(first.body!!["templateName"]).isNotNull()

        val second = openInvitation(rawToken)
        assertThat(second.body!!["status"]).isEqualTo("OPENED")
        assertThat(requestStatus(requestId)).isEqualTo("OPENED")
        val openedEvents = dsl.fetchCount(
            dsl.selectFrom(AUDIT_EVENT).where(
                AUDIT_EVENT.ACTION.eq("REFERENCE_REQUEST_OPENED")
                    .and(AUDIT_EVENT.ENTITY_ID.eq(requestId)),
            ),
        )
        assertThat(openedEvents).isEqualTo(1)
    }

    @Test
    fun `unknown invitation token returns 404`() {
        val response = openInvitation("definitely-not-a-token")
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // ---- email confirmation ----

    @Test
    fun `confirm email sets the recommender session cookie and consumes the token`() {
        val (rawToken, _) = sendInvitation("confirm_requester@example.com", "confirm_rec@corp.example.com")
        openInvitation(rawToken)

        val cookie = confirmEmail(rawToken, "confirm_rec@corp.example.com")
        assertThat(cookie).startsWith("verifolio_recommender_session=")

        // Consumed token: GET now 404, audit trail present.
        assertThat(openInvitation(rawToken).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditActions()).contains("RECOMMENDER_EMAIL_CONFIRMED", "INVITATION_TOKEN_CONSUMED")
    }

    @Test
    fun `wrong confirmation code returns 400 CODE_INVALID`() {
        val (rawToken, _) = sendInvitation("badcode_requester@example.com", "badcode_rec@corp.example.com")
        openInvitation(rawToken)
        assertThat(requestCode(rawToken).statusCode).isEqualTo(HttpStatus.ACCEPTED)

        val response = rest.exchange(
            "/api/v1/invitations/$rawToken/confirm-email", HttpMethod.POST,
            HttpEntity(mapOf("code" to "000000"), HttpHeaders().apply { set(HttpHeaders.CONTENT_TYPE, "application/json") }),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("CODE_INVALID")
    }

    // ---- one-click decline / report abuse ----

    @Test
    fun `one-click decline works before confirmation and stops the flow`() {
        val (rawToken, requestId) = sendInvitation("decline_requester@example.com", "decline_rec@corp.example.com")

        val response = rest.postForEntity("/api/v1/invitations/$rawToken/decline", null, Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(requestStatus(requestId)).isEqualTo("DECLINED")
        assertThat(auditActions()).contains("REQUEST_DECLINED")

        // Terminal request: open now 404, second decline 409.
        assertThat(openInvitation(rawToken).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val again = rest.postForEntity("/api/v1/invitations/$rawToken/decline", null, Map::class.java)
        assertThat(again.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `one-click decline still works after the token was consumed`() {
        val (rawToken, requestId) = sendInvitation("decline2_requester@example.com", "decline2_rec@corp.example.com")
        openInvitation(rawToken)
        confirmEmail(rawToken, "decline2_rec@corp.example.com")

        val response = rest.postForEntity("/api/v1/invitations/$rawToken/decline", null, Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(requestStatus(requestId)).isEqualTo("DECLINED")
    }

    @Test
    fun `report abuse declines with abuse metadata`() {
        val (rawToken, requestId) = sendInvitation("abuse_requester@example.com", "abuse_rec@corp.example.com")

        val response = rest.postForEntity("/api/v1/invitations/$rawToken/report-abuse", null, Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(requestStatus(requestId)).isEqualTo("DECLINED")

        val metadata = dsl.select(AUDIT_EVENT.METADATA).from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ACTION.eq("REQUEST_DECLINED"))
            .orderBy(AUDIT_EVENT.CREATED_AT.desc())
            .limit(1)
            .fetchOne(AUDIT_EVENT.METADATA)!!
        assertThat(metadata.data()).contains("abuse_report")
    }

    @Test
    fun `decline revokes outstanding invitation tokens`() {
        val (rawToken, requestId) = sendInvitation("revoke_requester@example.com", "revoke_rec@corp.example.com")
        rest.postForEntity("/api/v1/invitations/$rawToken/decline", null, Map::class.java)

        val it = INVITATION_TOKEN
        val row = dsl.selectFrom(it).where(it.REQUEST_ID.eq(UUID.fromString(requestId))).fetchOne()!!
        assertThat(row.revokedAt).isNotNull()
    }
}
