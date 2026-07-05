package com.verifolio.privacy

import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Shared requester/recommender drive helpers for the privacy integration tests (same patterns as
 * RecommenderPiiErasureTest). Drives a request all the way to COMPLETED (locked version + signals)
 * or DECLINED so the DSR paths have real data to act on.
 */
@Import(RecordingMailConfig::class)
abstract class PrivacyFlowSupport : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    protected data class Completed(val requestId: UUID, val contactId: UUID)

    protected fun login(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        return response.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("verifolio_session=") }.substringBefore(";")
    }

    protected fun xsrf(cookie: String): String? {
        val response = rest.exchange(
            "/api/v1/reference-requests", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        return response.headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
    }

    protected fun authHeaders(cookie: String, xsrfToken: String?): HttpHeaders = HttpHeaders().apply {
        add(HttpHeaders.COOKIE, cookie)
        set(HttpHeaders.CONTENT_TYPE, "application/json")
        if (xsrfToken != null) {
            add(HttpHeaders.COOKIE, "XSRF-TOKEN=$xsrfToken")
            add("X-XSRF-TOKEN", xsrfToken)
        }
    }

    private fun sendInvitation(requesterEmail: String, recommenderEmail: String): Pair<String, String> {
        val cookie = login(requesterEmail)
        val xsrfToken = xsrf(cookie)
        val contact = rest.exchange(
            "/api/v1/contacts", HttpMethod.POST,
            HttpEntity(
                mapOf("name" to "Rec Ommender", "email" to recommenderEmail, "relationshipType" to "MANAGER"),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        assertThat(contact.statusCode).isEqualTo(HttpStatus.CREATED)
        val templates = rest.exchange(
            "/api/v1/templates?locale=en", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val items = templates.body!!["items"] as List<Map<String, Any>>
        val templateId = (items.firstOrNull { it["type"] == "EMPLOYMENT_REFERENCE" } ?: items.first())["id"] as String
        val created = rest.exchange(
            "/api/v1/reference-requests", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "recommenderContactId" to (contact.body!!["id"] as String),
                    "templateId" to templateId,
                    "purpose" to "Visa support",
                    "verbalConsentAttested" to true,
                ),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        )
        val requestId = created.body!!["id"] as String
        val sent = rest.exchange(
            "/api/v1/reference-requests/$requestId/send", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(sent.statusCode).isEqualTo(HttpStatus.OK)
        val invitationEmail = mail.sent.last { it.to == recommenderEmail }
        val rawToken = Regex("/invitations/([A-Za-z0-9_-]+)").find(invitationEmail.textBody)!!.groupValues[1]
        return rawToken to requestId
    }

    private fun confirmEmail(rawToken: String, recommenderEmail: String): String {
        rest.exchange(
            "/api/v1/invitations/$rawToken/email-confirmations", HttpMethod.POST,
            HttpEntity<Void>(HttpHeaders()), Map::class.java,
        )
        val code = Regex("Code: (\\d{6})").find(mail.sent.last { it.to == recommenderEmail }.textBody)!!.groupValues[1]
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
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }), Map::class.java,
        )
        return response.headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
    }

    /** Drives a fresh request to COMPLETED (accepted → locked version with signals). */
    protected fun driveToCompleted(requester: String, recommender: String): Completed {
        val (rawToken, requestId) = sendInvitation(requester, recommender)
        rest.getForEntity("/api/v1/invitations/$rawToken", Map::class.java)
        val recCookie = confirmEmail(rawToken, recommender)
        val recXsrf = recommenderXsrf(recCookie)
        rest.exchange(
            "/api/v1/recommender/consent", HttpMethod.POST,
            HttpEntity(mapOf("accepted" to true), authHeaders(recCookie, recXsrf)), Map::class.java,
        )
        rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "approvedLetterText" to "An excellent colleague.",
                    "confirmationText" to "I confirm the information is accurate",
                    "recipientConfirmed" to true,
                    "relationshipConfirmed" to true,
                    "answersJson" to mapOf("q1" to "Great work"),
                ),
                authHeaders(recCookie, recommenderXsrf(recCookie)),
            ),
            Map::class.java,
        )
        // Owner accepts → COMPLETED, publishing the locked version and its signals.
        val ownerCookie = login(requester)
        val ownerXsrf = xsrf(ownerCookie)
        val accept = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(ownerCookie, ownerXsrf)), Map::class.java,
        )
        assertThat(accept.statusCode).isEqualTo(HttpStatus.OK)
        val rr = com.verifolio.jooq.tables.references.REFERENCE_REQUEST
        val contactId = dsl.select(rr.RECOMMENDER_CONTACT_ID).from(rr)
            .where(rr.ID.eq(UUID.fromString(requestId))).fetchOne(rr.RECOMMENDER_CONTACT_ID)!!
        return Completed(UUID.fromString(requestId), contactId)
    }

    /** Drives a fresh request to DECLINED via the one-click decline link (stamps declined_at). */
    protected fun driveToDeclined(requester: String, recommender: String): UUID {
        val (rawToken, requestId) = sendInvitation(requester, recommender)
        rest.getForEntity("/api/v1/invitations/$rawToken", Map::class.java)
        val decline = rest.exchange(
            "/api/v1/invitations/$rawToken/decline", HttpMethod.POST,
            HttpEntity<Void>(HttpHeaders()), Map::class.java,
        )
        assertThat(decline.statusCode).isEqualTo(HttpStatus.OK)
        return UUID.fromString(requestId)
    }
}
