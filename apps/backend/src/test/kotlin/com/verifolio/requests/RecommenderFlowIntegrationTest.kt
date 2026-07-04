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

    /** Fetch XSRF token using the recommender session cookie. */
    private fun recommenderXsrf(cookie: String): String? {
        val response = rest.exchange(
            "/api/v1/recommender/request", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        return response.headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
    }

    private fun acceptConsent(cookie: String, crossBorder: Boolean? = null): Map<*, *> {
        val xsrfToken = recommenderXsrf(cookie)
        val body = mutableMapOf<String, Any>("accepted" to true)
        if (crossBorder != null) body["crossBorderAccepted"] = crossBorder
        val response = rest.exchange(
            "/api/v1/recommender/consent", HttpMethod.POST,
            HttpEntity(body, authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
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

    // ---- consent gate, draft, submission ----

    @Test
    fun `full happy path from open to NEEDS_REVIEW`() {
        val (rawToken, requestId) = sendInvitation("happy_requester@example.com", "happy_rec@corp.example.com")
        openInvitation(rawToken)
        val cookie = confirmEmail(rawToken, "happy_rec@corp.example.com")

        // Context at the consent gate.
        val context = rest.exchange(
            "/api/v1/recommender/request", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(context.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(context.body!!["status"]).isEqualTo("OPENED")
        @Suppress("UNCHECKED_CAST")
        val consents = context.body!!["consents"] as Map<String, Map<String, Any>>
        assertThat(consents["processing"]!!["textId"]).isEqualTo("local-processing")

        assertThat(acceptConsent(cookie, crossBorder = true)["status"]).isEqualTo("IN_PROGRESS")

        val xsrfToken = recommenderXsrf(cookie)
        val draftResponse = rest.exchange(
            "/api/v1/recommender/response-draft", HttpMethod.PUT,
            HttpEntity(
                mapOf("answersJson" to mapOf("q1" to "Great colleague"), "approvedLetterText" to "Draft letter"),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        assertThat(draftResponse.statusCode).isEqualTo(HttpStatus.OK)

        val submitResponse = rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "approvedLetterText" to "Final approved letter text",
                    "confirmationText" to "I confirm this recommendation is accurate",
                    "recipientConfirmed" to true,
                    "relationshipConfirmed" to true,
                ),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        assertThat(submitResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(submitResponse.body!!["status"]).isEqualTo("NEEDS_REVIEW")
        assertThat(requestStatus(requestId)).isEqualTo("NEEDS_REVIEW")

        val rr = com.verifolio.jooq.tables.references.REFERENCE_RESPONSE
        val row = dsl.selectFrom(rr).where(rr.REQUEST_ID.eq(UUID.fromString(requestId))).fetchOne()!!
        assertThat(row.approvedLetterText).isEqualTo("Final approved letter text")
        assertThat(row.recipientConfirmed).isTrue()
        assertThat(row.relationshipConfirmed).isTrue()
        assertThat(row.submittedAt).isNotNull()
        assertThat(row.answersJson!!.data()).contains("Great colleague")

        val cr = com.verifolio.jooq.tables.references.CONSENT_RECORD
        val consentTypes = dsl.select(cr.CONSENT_TYPE).from(cr)
            .where(cr.REFERENCE_REQUEST_ID.eq(UUID.fromString(requestId)).and(cr.SUBJECT_TYPE.eq("RECOMMENDER")))
            .fetch(cr.CONSENT_TYPE)
        assertThat(consentTypes).containsExactlyInAnyOrder(
            "RECOMMENDER_PROCESSING_CONSENT",
            "CROSS_BORDER_TRANSFER_CONSENT",
        )

        assertThat(auditActions()).contains(
            "CONSENT_GRANTED", "REFERENCE_RESPONSE_STARTED", "REFERENCE_RESPONSE_SUBMITTED",
            "RECIPIENT_CONFIRMED_BY_RECOMMENDER", "RELATIONSHIP_CONFIRMED_BY_RECOMMENDER",
        )

        // Session revoked after submission.
        val afterSubmit = rest.exchange(
            "/api/v1/recommender/request", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(afterSubmit.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `declining at the consent gate records a DECLINED consent and revokes the session`() {
        val (rawToken, requestId) = sendInvitation("gate_requester@example.com", "gate_rec@corp.example.com")
        openInvitation(rawToken)
        val cookie = confirmEmail(rawToken, "gate_rec@corp.example.com")
        val xsrfToken = recommenderXsrf(cookie)

        val response = rest.exchange(
            "/api/v1/recommender/consent", HttpMethod.POST,
            HttpEntity(mapOf("accepted" to false), authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["status"]).isEqualTo("DECLINED")
        assertThat(requestStatus(requestId)).isEqualTo("DECLINED")

        val cr = com.verifolio.jooq.tables.references.CONSENT_RECORD
        val declined = dsl.selectFrom(cr)
            .where(cr.REFERENCE_REQUEST_ID.eq(UUID.fromString(requestId)).and(cr.SUBJECT_TYPE.eq("RECOMMENDER")))
            .fetchOne()!!
        assertThat(declined.status).isEqualTo("DECLINED")
        assertThat(declined.declinedAt).isNotNull()
        assertThat(declined.recommenderContactId).isNotNull()
        assertThat(declined.userId).isNull()
        assertThat(auditActions()).contains("CONSENT_DECLINED")

        val after = rest.exchange(
            "/api/v1/recommender/request", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(after.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `draft before consent acceptance returns 409`() {
        val (rawToken, _) = sendInvitation("early_requester@example.com", "early_rec@corp.example.com")
        openInvitation(rawToken)
        val cookie = confirmEmail(rawToken, "early_rec@corp.example.com")
        val xsrfToken = recommenderXsrf(cookie)

        val response = rest.exchange(
            "/api/v1/recommender/response-draft", HttpMethod.PUT,
            HttpEntity(mapOf("answersJson" to mapOf("q1" to "too early")), authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!["code"]).isEqualTo("INVALID_REQUEST_STATE")
    }

    @Test
    fun `submit without confirmations returns 400 CONFIRMATION_REQUIRED`() {
        val (rawToken, _) = sendInvitation("noconf_requester@example.com", "noconf_rec@corp.example.com")
        openInvitation(rawToken)
        val cookie = confirmEmail(rawToken, "noconf_rec@corp.example.com")
        acceptConsent(cookie)
        val xsrfToken = recommenderXsrf(cookie)

        val response = rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "approvedLetterText" to "Letter",
                    "recipientConfirmed" to false,
                    "relationshipConfirmed" to true,
                ),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("CONFIRMATION_REQUIRED")
    }

    @Test
    fun `submit twice returns 409`() {
        val (rawToken, _) = sendInvitation("twice_requester@example.com", "submit_twice_rec@corp.example.com")
        openInvitation(rawToken)
        val cookie = confirmEmail(rawToken, "submit_twice_rec@corp.example.com")
        acceptConsent(cookie)
        val xsrfToken = recommenderXsrf(cookie)

        val body = mapOf(
            "approvedLetterText" to "Letter",
            "recipientConfirmed" to true,
            "relationshipConfirmed" to true,
        )
        val first = rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(body, authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)

        // Session already revoked after submission → 401 (not 409): flow is closed.
        val second = rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(body, authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(second.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `user session on recommender endpoint returns 401`() {
        // SessionAuthFilter skips /api/v1/recommender/**, so a user cookie carries no
        // authentication there — the request is anonymous, not a wrong-principal 403.
        val userCookie = login("regular_user@example.com")

        val response = rest.exchange(
            "/api/v1/recommender/request", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, userCookie) }),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `recommender session does not authenticate user endpoints`() {
        val (rawToken, _) = sendInvitation("scope_requester@example.com", "scope_rec@corp.example.com")
        openInvitation(rawToken)
        val recommenderCookie = confirmEmail(rawToken, "scope_rec@corp.example.com")

        val response = rest.exchange(
            "/api/v1/contacts", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, recommenderCookie) }),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `browser with both cookies completes the recommender flow`() {
        val (rawToken, _) = sendInvitation("both_requester@example.com", "both_rec@corp.example.com")
        openInvitation(rawToken)
        val recommenderCookie = confirmEmail(rawToken, "both_rec@corp.example.com")
        val userCookie = login("some_other_user@example.com")

        val response = rest.exchange(
            "/api/v1/recommender/request", HttpMethod.GET,
            HttpEntity<Void>(
                HttpHeaders().apply {
                    add(HttpHeaders.COOKIE, userCookie)
                    add(HttpHeaders.COOKIE, recommenderCookie)
                },
            ),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["status"]).isEqualTo("OPENED")
    }

    @Test
    fun `code issuing is rate limited per invitation and refunded on mail failure`() {
        val (rawToken, _) = sendInvitation("limit_requester@example.com", "limit_rec@corp.example.com")
        openInvitation(rawToken)

        // Mail outage: three failed issues must not consume the window (refund on failure).
        mail.failFor = "limit_rec@corp.example.com"
        repeat(3) {
            assertThat(requestCode(rawToken).statusCode.is5xxServerError).isTrue()
        }
        mail.failFor = null

        // Full window still available: 3 successful issues, then the 4th is limited.
        repeat(3) {
            assertThat(requestCode(rawToken).statusCode).isEqualTo(HttpStatus.ACCEPTED)
        }
        val limited = requestCode(rawToken)
        assertThat(limited.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(limited.body!!["code"]).isEqualTo("RATE_LIMITED")
    }

    @Test
    fun `explicit cross-border refusal is recorded as a DECLINED consent`() {
        val (rawToken, requestId) = sendInvitation("xborder_requester@example.com", "xborder_rec@corp.example.com")
        openInvitation(rawToken)
        val cookie = confirmEmail(rawToken, "xborder_rec@corp.example.com")
        val xsrfToken = recommenderXsrf(cookie)

        val response = rest.exchange(
            "/api/v1/recommender/consent", HttpMethod.POST,
            HttpEntity(mapOf("accepted" to true, "crossBorderAccepted" to false), authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["status"]).isEqualTo("IN_PROGRESS")

        val cr = com.verifolio.jooq.tables.references.CONSENT_RECORD
        val crossBorder = dsl.selectFrom(cr)
            .where(
                cr.REFERENCE_REQUEST_ID.eq(UUID.fromString(requestId))
                    .and(cr.CONSENT_TYPE.eq("CROSS_BORDER_TRANSFER_CONSENT")),
            )
            .fetchOne()!!
        assertThat(crossBorder.status).isEqualTo("DECLINED")
        assertThat(crossBorder.declinedAt).isNotNull()
    }

    @Test
    fun `expired recommender session returns 401`() {
        val (rawToken, requestId) = sendInvitation("expire_requester@example.com", "expire_rec@corp.example.com")
        openInvitation(rawToken)
        val cookie = confirmEmail(rawToken, "expire_rec@corp.example.com")

        val rs = com.verifolio.jooq.tables.references.RECOMMENDER_SESSION
        dsl.update(rs)
            .set(rs.EXPIRES_AT, java.time.OffsetDateTime.now().minusMinutes(1))
            .where(rs.REQUEST_ID.eq(UUID.fromString(requestId)))
            .execute()

        val response = rest.exchange(
            "/api/v1/recommender/request", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
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
