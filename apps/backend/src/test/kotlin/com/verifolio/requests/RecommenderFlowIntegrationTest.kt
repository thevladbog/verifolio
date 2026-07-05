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
        val templateItems = templatesResponse.body!!["items"] as List<Map<String, Any>>
        // Deterministic template: EMPLOYMENT_REFERENCE maps to the REFERENCE_LETTER document type.
        val templateId = (templateItems.firstOrNull { it["type"] == "EMPLOYMENT_REFERENCE" } ?: templateItems.first())["id"] as String

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

    // ---- uploads ----

    private val pdfBytes = "%PDF-1.7 test scan content for verification".toByteArray(Charsets.US_ASCII)

    /** Drives to IN_PROGRESS (past the consent gate) and returns the recommender cookie. */
    private fun driveToInProgress(rawToken: String, recommenderEmail: String): String {
        openInvitation(rawToken)
        val cookie = confirmEmail(rawToken, recommenderEmail)
        acceptConsent(cookie)
        return cookie
    }

    private fun createUploadViaApi(
        cookie: String,
        kind: String,
        filename: String = "scan.pdf",
        mimeType: String = "application/pdf",
        sizeBytes: Long = pdfBytes.size.toLong(),
        sharedPublicly: Boolean = false,
        targetUploadId: String? = null,
    ): org.springframework.http.ResponseEntity<Map<*, *>> {
        val xsrfToken = recommenderXsrf(cookie)
        val body = mutableMapOf<String, Any>(
            "kind" to kind, "filename" to filename, "mimeType" to mimeType,
            "sizeBytes" to sizeBytes, "sharedPublicly" to sharedPublicly,
        )
        targetUploadId?.let { body["targetUploadId"] = it }
        return rest.exchange(
            "/api/v1/recommender/uploads", HttpMethod.POST,
            HttpEntity(body, authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
    }

    /** PUTs bytes to the presigned URL exactly as a browser would. */
    private fun putToPresignedUrl(url: String, bytes: ByteArray, contentType: String): Int {
        val client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build()
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI(url))
            .timeout(java.time.Duration.ofSeconds(30))
            .header("Content-Type", contentType)
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        return client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun confirmUploadViaApi(cookie: String, uploadId: String): Map<*, *> {
        val xsrfToken = recommenderXsrf(cookie)
        val response = rest.exchange(
            "/api/v1/recommender/uploads/$uploadId/confirm", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    @Test
    fun `upload lifecycle - presigned put, confirm READY, consent recorded for shared upload`() {
        val (rawToken, requestId) = sendInvitation("upl_requester@example.com", "upl_rec@corp.example.com")
        val cookie = driveToInProgress(rawToken, "upl_rec@corp.example.com")

        val created = createUploadViaApi(cookie, "SCAN", sharedPublicly = true)
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val uploadId = created.body!!["uploadId"] as String
        val uploadUrl = created.body!!["uploadUrl"] as String

        assertThat(putToPresignedUrl(uploadUrl, pdfBytes, "application/pdf")).isEqualTo(200)

        val confirmed = confirmUploadViaApi(cookie, uploadId)
        assertThat(confirmed["status"]).isEqualTo("READY")
        val expectedSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(pdfBytes).joinToString("") { "%02x".format(it) }
        assertThat(confirmed["sha256"]).isEqualTo(expectedSha)

        // Per-upload sharing consent recorded and linked.
        val ru = com.verifolio.jooq.tables.references.RESPONSE_UPLOAD
        val uploadRow = dsl.selectFrom(ru).where(ru.ID.eq(UUID.fromString(uploadId))).fetchOne()!!
        assertThat(uploadRow.consentRecordId).isNotNull()
        val cr = com.verifolio.jooq.tables.references.CONSENT_RECORD
        val consent = dsl.selectFrom(cr).where(cr.ID.eq(uploadRow.consentRecordId)).fetchOne()!!
        assertThat(consent.consentType).isEqualTo("RECOMMENDER_PUBLIC_SHARING_CONSENT")
        assertThat(consent.status).isEqualTo("GRANTED")
        assertThat(auditActions()).contains("FILE_UPLOAD_REQUESTED", "FILE_UPLOADED", "FILE_VALIDATED")
    }

    @Test
    fun `mismatched content is rejected`() {
        val (rawToken, _) = sendInvitation("uplrej_requester@example.com", "uplrej_rec@corp.example.com")
        val cookie = driveToInProgress(rawToken, "uplrej_rec@corp.example.com")

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val created = createUploadViaApi(cookie, "SCAN", mimeType = "application/pdf", sizeBytes = png.size.toLong())
        val uploadId = created.body!!["uploadId"] as String
        assertThat(putToPresignedUrl(created.body!!["uploadUrl"] as String, png, "application/pdf")).isEqualTo(200)

        val confirmed = confirmUploadViaApi(cookie, uploadId)
        assertThat(confirmed["status"]).isEqualTo("REJECTED")
        assertThat(confirmed["reason"] as String).contains("declared type")

        val fo = com.verifolio.jooq.tables.references.FILE_OBJECT
        val ru = com.verifolio.jooq.tables.references.RESPONSE_UPLOAD
        val fileId = dsl.select(ru.FILE_OBJECT_ID).from(ru)
            .where(ru.ID.eq(UUID.fromString(uploadId))).fetchOne(ru.FILE_OBJECT_ID)!!
        assertThat(dsl.select(fo.STATUS).from(fo).where(fo.ID.eq(fileId)).fetchOne(fo.STATUS))
            .isEqualTo("REJECTED")
    }

    @Test
    fun `detached signature requires a confirmed target`() {
        val (rawToken, _) = sendInvitation("sig_requester@example.com", "sig_rec@corp.example.com")
        val cookie = driveToInProgress(rawToken, "sig_rec@corp.example.com")

        // No target at all → 409.
        val without = createUploadViaApi(
            cookie, "DETACHED_SIGNATURE",
            filename = "sig.p7s", mimeType = "application/pkcs7-signature", sizeBytes = 5,
        )
        assertThat(without.statusCode).isEqualTo(HttpStatus.CONFLICT)

        // With a READY scan target → 201, and the signature confirms fine.
        val scan = createUploadViaApi(cookie, "SCAN")
        val scanId = scan.body!!["uploadId"] as String
        putToPresignedUrl(scan.body!!["uploadUrl"] as String, pdfBytes, "application/pdf")
        confirmUploadViaApi(cookie, scanId)

        val der = byteArrayOf(0x30, 0x82.toByte(), 1, 2, 3)
        val sig = createUploadViaApi(
            cookie, "DETACHED_SIGNATURE",
            filename = "sig.p7s", mimeType = "application/pkcs7-signature",
            sizeBytes = der.size.toLong(), targetUploadId = scanId,
        )
        assertThat(sig.statusCode).isEqualTo(HttpStatus.CREATED)
        putToPresignedUrl(sig.body!!["uploadUrl"] as String, der, "application/pkcs7-signature")
        assertThat(confirmUploadViaApi(cookie, sig.body!!["uploadId"] as String)["status"]).isEqualTo("READY")

        // The target now has a dependent signature → deleting it is blocked.
        val xsrfToken = recommenderXsrf(cookie)
        val deleteTarget = rest.exchange(
            "/api/v1/recommender/uploads/$scanId", HttpMethod.DELETE,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(deleteTarget.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `delete removes the upload before submission`() {
        val (rawToken, _) = sendInvitation("upldel_requester@example.com", "upldel_rec@corp.example.com")
        val cookie = driveToInProgress(rawToken, "upldel_rec@corp.example.com")

        val created = createUploadViaApi(cookie, "ATTACHMENT")
        val uploadId = created.body!!["uploadId"] as String
        putToPresignedUrl(created.body!!["uploadUrl"] as String, pdfBytes, "application/pdf")
        confirmUploadViaApi(cookie, uploadId)

        val xsrfToken = recommenderXsrf(cookie)
        val deleted = rest.exchange(
            "/api/v1/recommender/uploads/$uploadId", HttpMethod.DELETE,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(deleted.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val list = rest.exchange(
            "/api/v1/recommender/uploads", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        assertThat(list.body!!["items"] as List<Map<String, Any>>).isEmpty()
        assertThat(auditActions()).contains("FILE_DELETED")
    }

    // ---- recipient accept / correction (Flow 4) ----

    /** Drives the recommender through open→code→consent→submit; returns nothing (request is NEEDS_REVIEW). */
    private fun driveToNeedsReview(rawToken: String, recommenderEmail: String) {
        openInvitation(rawToken)
        val cookie = confirmEmail(rawToken, recommenderEmail)
        acceptConsent(cookie)
        val xsrfToken = recommenderXsrf(cookie)
        val submit = rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "approvedLetterText" to "An excellent colleague.\nHighly recommended.",
                    "confirmationText" to "I confirm the information is accurate",
                    "recipientConfirmed" to true,
                    "relationshipConfirmed" to true,
                    "answersJson" to mapOf("q1" to "Great work"),
                ),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        assertThat(submit.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    /** Requester-side login + XSRF for accept/correction calls. */
    private fun requesterSession(email: String): Pair<String, String?> {
        val cookie = login(email)
        return cookie to xsrf(cookie)
    }

    @Test
    fun `accept generates a locked document with pdf in storage and verification signals`() {
        val (rawToken, requestId) = sendInvitation("accept_requester@example.com", "accept_rec@corp.example.com")
        driveToNeedsReview(rawToken, "accept_rec@corp.example.com")
        val (cookie, xsrfToken) = requesterSession("accept_requester@example.com")

        val response = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val requestDto = response.body!!["request"] as Map<String, Any>
        assertThat(requestDto["status"]).isEqualTo("COMPLETED")
        val documentId = response.body!!["documentId"] as String
        assertThat(requestStatus(requestId)).isEqualTo("COMPLETED")

        // Locked version 1 with hashes; PDF FileObject READY; bytes verifiable via presigned URL.
        val dv = com.verifolio.jooq.tables.references.DOCUMENT_VERSION
        val version = dsl.selectFrom(dv)
            .where(dv.DOCUMENT_ID.eq(UUID.fromString(documentId)))
            .fetchOne()!!
        assertThat(version.versionNumber).isEqualTo(1)
        assertThat(version.status).isEqualTo("LOCKED")
        assertThat(version.lockedAt).isNotNull()
        assertThat(version.sha256Hash).hasSize(64)

        val fo = com.verifolio.jooq.tables.references.FILE_OBJECT
        val file = dsl.selectFrom(fo).where(fo.ID.eq(version.pdfFileId)).fetchOne()!!
        assertThat(file.status).isEqualTo("READY")
        assertThat(file.purpose).isEqualTo("GENERATED_PDF")

        // Verification signals: 6 (corporate domain corp.example.com is not deny-listed).
        val vs = com.verifolio.jooq.tables.references.VERIFICATION_SIGNAL
        val signalTypes = dsl.select(vs.SIGNAL_TYPE).from(vs)
            .where(vs.ENTITY_ID.eq(version.id).or(vs.ENTITY_ID.`in`(
                dsl.select(com.verifolio.jooq.tables.references.REFERENCE_RESPONSE.ID)
                    .from(com.verifolio.jooq.tables.references.REFERENCE_RESPONSE)
                    .where(com.verifolio.jooq.tables.references.REFERENCE_RESPONSE.REQUEST_ID.eq(UUID.fromString(requestId))),
            )))
            .fetch(vs.SIGNAL_TYPE)
        assertThat(signalTypes).containsExactlyInAnyOrder(
            "RECIPIENT_CONFIRMED", "RECOMMENDER_RELATIONSHIP_CONFIRMED", "EMAIL_CONFIRMED",
            "CORPORATE_DOMAIN_CONFIRMED", "VERSION_LOCKED", "DOCUMENT_HASH_LOCKED",
        )

        assertThat(auditActions()).contains(
            "REFERENCE_RESPONSE_ACCEPTED", "DOCUMENT_CREATED", "DOCUMENT_VERSION_CREATED",
            "DOCUMENT_PDF_GENERATED", "DOCUMENT_VERSION_LOCKED", "FILE_UPLOADED",
            "VERIFICATION_SIGNAL_CREATED",
        )

        // Download URL works and the fetched bytes hash to FileObject.sha256_hash.
        val linkResponse = rest.exchange(
            "/api/v1/documents/$documentId/versions/1/download-url", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(linkResponse.statusCode).isEqualTo(HttpStatus.OK)
        val url = linkResponse.body!!["url"] as String
        val pdfBytes = java.net.URI(url).toURL().readBytes()
        assertThat(String(pdfBytes.copyOfRange(0, 5), Charsets.US_ASCII)).isEqualTo("%PDF-")
        val fetchedSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(pdfBytes).joinToString("") { "%02x".format(it) }
        assertThat(fetchedSha).isEqualTo(file.sha256Hash)
        assertThat(auditActions()).contains("FILE_DOWNLOAD_GRANTED")
    }

    @Test
    fun `free email domain skips the corporate domain signal`() {
        val (rawToken, requestId) = sendInvitation("gmail_requester@example.com", "friendly.rec@gmail.com")
        driveToNeedsReview(rawToken, "friendly.rec@gmail.com")
        val (cookie, xsrfToken) = requesterSession("gmail_requester@example.com")

        val response = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val vs = com.verifolio.jooq.tables.references.VERIFICATION_SIGNAL
        val rr = com.verifolio.jooq.tables.references.REFERENCE_RESPONSE
        val signalTypes = dsl.select(vs.SIGNAL_TYPE).from(vs)
            .where(vs.ENTITY_TYPE.eq("REFERENCE_RESPONSE").and(vs.ENTITY_ID.`in`(
                dsl.select(rr.ID).from(rr).where(rr.REQUEST_ID.eq(UUID.fromString(requestId))),
            )))
            .fetch(vs.SIGNAL_TYPE)
        assertThat(signalTypes).doesNotContain("CORPORATE_DOMAIN_CONFIRMED")
        assertThat(signalTypes).contains("EMAIL_CONFIRMED")
    }

    @Test
    fun `accept from wrong status and double accept return 409, foreign returns 404`() {
        val (rawToken, requestId) = sendInvitation("accept409_requester@example.com", "accept409_rec@corp.example.com")
        val (cookie, xsrfToken) = requesterSession("accept409_requester@example.com")

        // Still SENT — no submission yet.
        val early = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(early.statusCode).isEqualTo(HttpStatus.CONFLICT)

        driveToNeedsReview(rawToken, "accept409_rec@corp.example.com")

        val (foreignCookie, foreignXsrf) = requesterSession("accept_foreign@example.com")
        val foreign = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(foreignCookie, foreignXsrf)),
            Map::class.java,
        )
        assertThat(foreign.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(
            rest.exchange(
                "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
                HttpEntity<Void>(authHeaders(cookie, xsrfToken)), Map::class.java,
            ).statusCode,
        ).isEqualTo(HttpStatus.OK)

        val second = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(second.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    // Note: a correction is only possible BEFORE acceptance (COMPLETED is terminal), so an
    // MVP request yields exactly one accepted version; the multi-version path arrives with
    // DSR CORRECTION. This test verifies the full correction cycle ends in the updated text.
    @Test
    fun `correction cycle re-invites the recommender and locks the corrected version`() {
        val (rawToken, requestId) = sendInvitation("corr_requester@example.com", "corr_rec@corp.example.com")
        driveToNeedsReview(rawToken, "corr_rec@corp.example.com")
        val (cookie, xsrfToken) = requesterSession("corr_requester@example.com")

        // Request a correction with a note.
        val correction = rest.exchange(
            "/api/v1/reference-requests/$requestId/request-correction", HttpMethod.POST,
            HttpEntity(mapOf("message" to "Please mention the 2024 project"), authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(correction.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(correction.body!!["status"]).isEqualTo("CORRECTION_REQUESTED")
        assertThat(auditActions()).contains("REQUEST_CORRECTION_REQUESTED")

        // Recommender receives a fresh invitation and returns.
        val correctionEmail = mail.sent.last { it.to == "corr_rec@corp.example.com" }
        assertThat(correctionEmail.textBody).contains("Please mention the 2024 project")
        val newToken = Regex("/invitations/([A-Za-z0-9_-]+)")
            .find(correctionEmail.textBody)!!.groupValues[1]

        openInvitation(newToken)
        val recCookie = confirmEmail(newToken, "corr_rec@corp.example.com")
        val recXsrf = recommenderXsrf(recCookie)

        // First draft save flips CORRECTION_REQUESTED -> IN_PROGRESS.
        val draft = rest.exchange(
            "/api/v1/recommender/response-draft", HttpMethod.PUT,
            HttpEntity(
                mapOf("answersJson" to mapOf("q1" to "Updated"), "approvedLetterText" to "Updated letter"),
                authHeaders(recCookie, recXsrf),
            ),
            Map::class.java,
        )
        assertThat(draft.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(requestStatus(requestId)).isEqualTo("IN_PROGRESS")

        val resubmit = rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "approvedLetterText" to "Updated letter mentioning the 2024 project.",
                    "recipientConfirmed" to true,
                    "relationshipConfirmed" to true,
                ),
                authHeaders(recCookie, recXsrf),
            ),
            Map::class.java,
        )
        assertThat(resubmit.statusCode).isEqualTo(HttpStatus.CREATED)

        // The only acceptance in this request's life — it locks version 1 with the corrected text.
        val secondAccept = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrf(cookie))),
            Map::class.java,
        )
        assertThat(secondAccept.statusCode).isEqualTo(HttpStatus.OK)
        val documentId = secondAccept.body!!["documentId"] as String

        val dv = com.verifolio.jooq.tables.references.DOCUMENT_VERSION
        val versions = dsl.selectFrom(dv)
            .where(dv.DOCUMENT_ID.eq(UUID.fromString(documentId)))
            .orderBy(dv.VERSION_NUMBER.asc())
            .fetch()
        assertThat(versions).hasSize(1)
        assertThat(versions[0].status).isEqualTo("LOCKED")
        // The locked version carries the corrected (resubmitted) letter text.
        assertThat(versions[0].contentJson!!.data()).contains("Updated letter mentioning the 2024 project.")

        val d = com.verifolio.jooq.tables.references.DOCUMENT
        val doc = dsl.selectFrom(d).where(d.ID.eq(UUID.fromString(documentId))).fetchOne()!!
        assertThat(doc.currentVersionId).isEqualTo(versions[0].id)
        assertThat(requestStatus(requestId)).isEqualTo("COMPLETED")
    }

    @Test
    fun `one-click decline works from a correction re-invite`() {
        val (rawToken, requestId) = sendInvitation("corrdecl_requester@example.com", "corrdecl_rec@corp.example.com")
        driveToNeedsReview(rawToken, "corrdecl_rec@corp.example.com")
        val (cookie, xsrfToken) = requesterSession("corrdecl_requester@example.com")

        rest.exchange(
            "/api/v1/reference-requests/$requestId/request-correction", HttpMethod.POST,
            HttpEntity(mapOf<String, Any>(), authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        val newToken = Regex("/invitations/([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == "corrdecl_rec@corp.example.com" }.textBody)!!.groupValues[1]

        // The correction email advertises the decline link — it must work from CORRECTION_REQUESTED.
        val decline = rest.postForEntity("/api/v1/invitations/$newToken/decline", null, Map::class.java)
        assertThat(decline.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(requestStatus(requestId)).isEqualTo("DECLINED")
    }

    @Test
    fun `direct submit without an autosave works in the correction cycle`() {
        val (rawToken, requestId) = sendInvitation("directsub_requester@example.com", "directsub_rec@corp.example.com")
        driveToNeedsReview(rawToken, "directsub_rec@corp.example.com")
        val (cookie, xsrfToken) = requesterSession("directsub_requester@example.com")

        rest.exchange(
            "/api/v1/reference-requests/$requestId/request-correction", HttpMethod.POST,
            HttpEntity(mapOf<String, Any>(), authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        val newToken = Regex("/invitations/([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == "directsub_rec@corp.example.com" }.textBody)!!.groupValues[1]

        openInvitation(newToken)
        val recCookie = confirmEmail(newToken, "directsub_rec@corp.example.com")
        val recXsrf = recommenderXsrf(recCookie)

        // No PUT /response-draft first — submit directly from CORRECTION_REQUESTED.
        val submit = rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "approvedLetterText" to "Directly resubmitted corrected letter.",
                    "recipientConfirmed" to true,
                    "relationshipConfirmed" to true,
                ),
                authHeaders(recCookie, recXsrf),
            ),
            Map::class.java,
        )
        assertThat(submit.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(requestStatus(requestId)).isEqualTo("NEEDS_REVIEW")
    }

    @Test
    fun `accepted uploads become attachments with scan and signature signals`() {
        val (rawToken, requestId) = sendInvitation("attach_requester@example.com", "attach_rec@corp.example.com")
        val cookie = driveToInProgress(rawToken, "attach_rec@corp.example.com")

        // Scan (shared) + detached signature covering it.
        val scan = createUploadViaApi(cookie, "SCAN", sharedPublicly = true)
        val scanId = scan.body!!["uploadId"] as String
        putToPresignedUrl(scan.body!!["uploadUrl"] as String, pdfBytes, "application/pdf")
        confirmUploadViaApi(cookie, scanId)

        val der = byteArrayOf(0x30, 0x82.toByte(), 1, 2, 3)
        val sig = createUploadViaApi(
            cookie, "DETACHED_SIGNATURE", filename = "sig.p7s",
            mimeType = "application/pkcs7-signature", sizeBytes = der.size.toLong(), targetUploadId = scanId,
        )
        putToPresignedUrl(sig.body!!["uploadUrl"] as String, der, "application/pkcs7-signature")
        confirmUploadViaApi(cookie, sig.body!!["uploadId"] as String)

        // Submit + accept.
        val recXsrf = recommenderXsrf(cookie)
        rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(
                mapOf("approvedLetterText" to "Letter with evidence.", "recipientConfirmed" to true, "relationshipConfirmed" to true),
                authHeaders(cookie, recXsrf),
            ),
            Map::class.java,
        )
        val (reqCookie, reqXsrf) = requesterSession("attach_requester@example.com")
        val accept = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(reqCookie, reqXsrf)), Map::class.java,
        )
        assertThat(accept.statusCode).isEqualTo(HttpStatus.OK)
        val documentId = accept.body!!["documentId"] as String

        // Two attachments on the locked version.
        val dv = com.verifolio.jooq.tables.references.DOCUMENT_VERSION
        val da = com.verifolio.jooq.tables.references.DOCUMENT_ATTACHMENT
        val versionId = dsl.select(dv.ID).from(dv)
            .where(dv.DOCUMENT_ID.eq(UUID.fromString(documentId))).fetchOne(dv.ID)!!
        val attachmentTypes = dsl.select(da.TYPE).from(da)
            .where(da.DOCUMENT_VERSION_ID.eq(versionId)).fetch(da.TYPE)
        assertThat(attachmentTypes).containsExactlyInAnyOrder("SCAN", "DETACHED_SIGNATURE")

        // Signals with target evidence.
        val vs = com.verifolio.jooq.tables.references.VERIFICATION_SIGNAL
        val signals = dsl.selectFrom(vs)
            .where(vs.ENTITY_TYPE.eq("DOCUMENT_VERSION").and(vs.ENTITY_ID.eq(versionId)))
            .fetch()
        assertThat(signals.map { it.signalType }).contains("SCAN_ATTACHED", "SIGNATURE_ATTACHED")
        val sigSignal = signals.first { it.signalType == "SIGNATURE_ATTACHED" }
        assertThat(sigSignal.evidenceJson!!.data()).contains("targetFileId")
        assertThat(sigSignal.evidenceJson!!.data()).doesNotContain("unknown")
    }

    @Test
    fun `documents list and detail are owner scoped`() {
        val (rawToken, requestId) = sendInvitation("doclist_requester@example.com", "doclist_rec@corp.example.com")
        driveToNeedsReview(rawToken, "doclist_rec@corp.example.com")
        val (cookie, xsrfToken) = requesterSession("doclist_requester@example.com")
        val accept = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)), Map::class.java,
        )
        val documentId = accept.body!!["documentId"] as String

        val list = rest.exchange(
            "/api/v1/documents", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val items = list.body!!["items"] as List<Map<String, Any>>
        assertThat(items.map { it["id"] }).contains(documentId)
        assertThat(items.first { it["id"] == documentId }["type"]).isEqualTo("REFERENCE_LETTER")

        val foreignCookie = login("doclist_other@example.com")
        val foreignDetail = rest.exchange(
            "/api/v1/documents/$documentId", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, foreignCookie) }),
            Map::class.java,
        )
        assertThat(foreignDetail.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        val detail = rest.exchange(
            "/api/v1/documents/$documentId", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(detail.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(detail.body!!["currentVersionNumber"]).isEqualTo(1)
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
