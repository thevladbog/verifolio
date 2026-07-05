package com.verifolio.requests

import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/** GET /api/v1/reference-requests/{id}/response — owner review of the submitted response (Flow 4). */
@Import(RecordingMailConfig::class)
class ResponseReviewIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    // ---- helpers (same patterns as RecommenderFlowIntegrationTest) ----

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

    /** Requester creates contact + request and sends it; returns raw invitation token + request id. */
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

    // ---- recommender-side helpers ----

    private fun confirmEmail(rawToken: String, recommenderEmail: String): String {
        val issue = rest.exchange(
            "/api/v1/invitations/$rawToken/email-confirmations", HttpMethod.POST,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )
        assertThat(issue.statusCode).isEqualTo(HttpStatus.ACCEPTED)
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

    private fun acceptConsent(cookie: String) {
        val xsrfToken = recommenderXsrf(cookie)
        val response = rest.exchange(
            "/api/v1/recommender/consent", HttpMethod.POST,
            HttpEntity(mapOf("accepted" to true), authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    private val pdfBytes = "%PDF-1.7 review test scan content".toByteArray(Charsets.US_ASCII)

    /** Creates a READY SCAN upload via the recommender uploads API; returns the upload id. */
    private fun createConfirmedUpload(cookie: String, sharedPublicly: Boolean): String {
        val xsrfToken = recommenderXsrf(cookie)
        val created = rest.exchange(
            "/api/v1/recommender/uploads", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "kind" to "SCAN", "filename" to "scan.pdf", "mimeType" to "application/pdf",
                    "sizeBytes" to pdfBytes.size.toLong(), "sharedPublicly" to sharedPublicly,
                ),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val uploadId = created.body!!["uploadId"] as String
        val uploadUrl = created.body!!["uploadUrl"] as String

        val client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build()
        val put = java.net.http.HttpRequest.newBuilder(java.net.URI(uploadUrl))
            .timeout(java.time.Duration.ofSeconds(30))
            .header("Content-Type", "application/pdf")
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(pdfBytes))
            .build()
        assertThat(client.send(put, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200)

        val confirmed = rest.exchange(
            "/api/v1/recommender/uploads/$uploadId/confirm", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, recommenderXsrf(cookie))),
            Map::class.java,
        )
        assertThat(confirmed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(confirmed.body!!["status"]).isEqualTo("READY")
        return uploadId
    }

    private fun submitResponse(cookie: String) {
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

    private fun getResponseView(cookie: String, requestId: String) = rest.exchange(
        "/api/v1/reference-requests/$requestId/response", HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
        Map::class.java,
    )

    // ---- tests ----

    @Test
    fun `owner reads letter, answers and upload metadata in NEEDS_REVIEW and still after accept`() {
        val (rawToken, requestId) = sendInvitation("review_requester@example.com", "review_rec@corp.example.com")
        rest.getForEntity("/api/v1/invitations/$rawToken", Map::class.java)
        val recCookie = confirmEmail(rawToken, "review_rec@corp.example.com")
        acceptConsent(recCookie)
        val uploadId = createConfirmedUpload(recCookie, sharedPublicly = true)
        submitResponse(recCookie)

        val cookie = login("review_requester@example.com")
        val response = getResponseView(cookie, requestId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["approvedLetterText"]).isEqualTo("An excellent colleague.\nHighly recommended.")
        assertThat(body["submittedAt"]).isNotNull()
        assertThat(body["recipientConfirmed"]).isEqualTo(true)
        assertThat(body["relationshipConfirmed"]).isEqualTo(true)
        @Suppress("UNCHECKED_CAST")
        val answers = body["answers"] as Map<String, Any?>
        assertThat(answers["q1"]).isEqualTo("Great work")

        @Suppress("UNCHECKED_CAST")
        val uploads = body["uploads"] as List<Map<String, Any?>>
        assertThat(uploads).hasSize(1)
        val upload = uploads.single()
        assertThat(upload["id"]).isEqualTo(uploadId)
        assertThat(upload["kind"]).isEqualTo("SCAN")
        assertThat(upload["contentType"]).isEqualTo("application/pdf")
        assertThat((upload["sizeBytes"] as Number).toLong()).isEqualTo(pdfBytes.size.toLong())
        assertThat(upload["sharedPublicly"]).isEqualTo(true)
        assertThat(upload["targetUploadId"]).isNull()
        // Metadata only — no storage URLs or download links before acceptance.
        assertThat(upload.keys).doesNotContain("url", "downloadUrl", "storageKey")

        // Accept → COMPLETED; the response stays readable.
        val xsrfToken = xsrf(cookie)
        val accept = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(accept.statusCode).isEqualTo(HttpStatus.OK)

        val afterAccept = getResponseView(cookie, requestId)
        assertThat(afterAccept.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(afterAccept.body!!["approvedLetterText"]).isEqualTo("An excellent colleague.\nHighly recommended.")
    }

    @Test
    fun `another user gets 404 for a foreign request with a submitted response`() {
        val (rawToken, requestId) = sendInvitation("foreign_owner@example.com", "foreign_rec@corp.example.com")
        rest.getForEntity("/api/v1/invitations/$rawToken", Map::class.java)
        val recCookie = confirmEmail(rawToken, "foreign_rec@corp.example.com")
        acceptConsent(recCookie)
        submitResponse(recCookie)

        val foreignCookie = login("foreign_reader@example.com")
        val response = getResponseView(foreignCookie, requestId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["code"]).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `before submission the owner gets 404`() {
        val (_, requestId) = sendInvitation("early_owner@example.com", "early_rec@corp.example.com")

        val cookie = login("early_owner@example.com")
        val response = getResponseView(cookie, requestId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["code"]).isEqualTo("NOT_FOUND")
    }
}
