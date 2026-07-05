package com.verifolio.requests

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.INVITATION_TOKEN
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import com.verifolio.workflows.RecurringTask
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime
import java.util.UUID

@Import(RecordingMailConfig::class)
class ReferenceRequestLifecycleTaskTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var context: ApplicationContext

    private val task: RecurringTask by lazy {
        context.getBeansOfType(RecurringTask::class.java).values
            .first { it.name == "reference-request-lifecycle" }
    }

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    // ---- requester helpers (established pattern) ----

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

    /** Creates + sends a request; returns the request id. */
    private fun sendRequest(requesterEmail: String, recommenderEmail: String): String {
        val cookie = login(requesterEmail)
        val xsrfToken = xsrf(cookie)
        val contactId = rest.exchange(
            "/api/v1/contacts", HttpMethod.POST,
            HttpEntity(
                mapOf("name" to "Rec Ommender", "email" to recommenderEmail, "relationshipType" to "MANAGER"),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        ).body!!["id"] as String
        @Suppress("UNCHECKED_CAST")
        val templates = rest.exchange(
            "/api/v1/templates?locale=en", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        ).body!!["items"] as List<Map<String, Any>>
        val templateId = (templates.firstOrNull { it["type"] == "EMPLOYMENT_REFERENCE" } ?: templates.first())["id"] as String
        val requestId = rest.exchange(
            "/api/v1/reference-requests", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "recommenderContactId" to contactId, "templateId" to templateId,
                    "purpose" to "Lifecycle test", "verbalConsentAttested" to true,
                ),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        ).body!!["id"] as String
        val send = rest.exchange(
            "/api/v1/reference-requests/$requestId/send", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)), Map::class.java,
        )
        assertThat(send.statusCode).isEqualTo(HttpStatus.OK)
        return requestId
    }

    private fun backdateSentAt(requestId: String, days: Long) {
        val rr = REFERENCE_REQUEST
        dsl.update(rr)
            .set(rr.SENT_AT, OffsetDateTime.now().minusDays(days))
            .where(rr.ID.eq(UUID.fromString(requestId)))
            .execute()
    }

    private fun remindersSent(requestId: String): Int {
        val rr = REFERENCE_REQUEST
        return dsl.select(rr.REMINDERS_SENT).from(rr)
            .where(rr.ID.eq(UUID.fromString(requestId))).fetchOne(rr.REMINDERS_SENT)!!
    }

    private fun status(requestId: String): String {
        val rr = REFERENCE_REQUEST
        return dsl.select(rr.STATUS).from(rr)
            .where(rr.ID.eq(UUID.fromString(requestId))).fetchOne(rr.STATUS)!!
    }

    // ---- reminders ----

    @Test
    fun `due reminder sends a working fresh link and increments exactly once`() {
        val requestId = sendRequest("lc1_requester@example.com", "lc1_rec@corp.example.com")
        backdateSentAt(requestId, 4)
        mail.sent.clear()

        task.run()

        val reminder = mail.sent.single { it.to == "lc1_rec@corp.example.com" }
        assertThat(reminder.subject).contains("Reminder")
        assertThat(reminder.textBody).contains("/stop-reminders")
        val rawToken = Regex("Open the request: .*/invitations/([A-Za-z0-9_-]+)")
            .find(reminder.textBody)!!.groupValues[1]
        // The re-minted link works.
        val open = rest.exchange(
            "/api/v1/invitations/$rawToken", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()), Map::class.java,
        )
        assertThat(open.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(remindersSent(requestId)).isEqualTo(1)

        // Idempotency: an immediate second tick sends nothing more.
        mail.sent.clear()
        task.run()
        assertThat(mail.sent.filter { it.to == "lc1_rec@corp.example.com" }).isEmpty()
        assertThat(remindersSent(requestId)).isEqualTo(1)

        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("REFERENCE_REQUEST_REMINDER_SENT")
    }

    @Test
    fun `day-14 reminder carries the expiration warning`() {
        val requestId = sendRequest("lc2_requester@example.com", "lc2_rec@corp.example.com")
        val rr = REFERENCE_REQUEST
        dsl.update(rr)
            .set(rr.SENT_AT, OffsetDateTime.now().minusDays(15))
            .set(rr.REMINDERS_SENT, 2)
            .where(rr.ID.eq(UUID.fromString(requestId)))
            .execute()
        mail.sent.clear()

        task.run()

        val warning = mail.sent.single { it.to == "lc2_rec@corp.example.com" }
        assertThat(warning.subject).contains("expires soon")
        assertThat(warning.textBody).contains("expires soon")
        assertThat(remindersSent(requestId)).isEqualTo(3)

        // All three sent — no further reminders even when overdue.
        mail.sent.clear()
        task.run()
        assertThat(mail.sent.filter { it.to == "lc2_rec@corp.example.com" }).isEmpty()
    }

    @Test
    fun `stop-reminders link halts the schedule`() {
        val requestId = sendRequest("lc3_requester@example.com", "lc3_rec@corp.example.com")
        val invitationToken = Regex("/invitations/([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == "lc3_rec@corp.example.com" }.textBody)!!.groupValues[1]

        val stop = rest.postForEntity("/api/v1/invitations/$invitationToken/stop-reminders", null, Map::class.java)
        assertThat(stop.statusCode).isEqualTo(HttpStatus.OK)

        backdateSentAt(requestId, 4)
        mail.sent.clear()
        task.run()

        assertThat(mail.sent.filter { it.to == "lc3_rec@corp.example.com" }).isEmpty()
        assertThat(remindersSent(requestId)).isEqualTo(0)
        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("REMINDERS_STOPPED")
    }

    @Test
    fun `mail failure leaves the reminder pending for the next tick`() {
        val requestId = sendRequest("lc4_requester@example.com", "lc4_rec@corp.example.com")
        backdateSentAt(requestId, 4)
        mail.failFor = "lc4_rec@corp.example.com"

        task.run()
        assertThat(remindersSent(requestId)).isEqualTo(0)

        mail.failFor = null
        mail.sent.clear()
        task.run()
        assertThat(mail.sent.filter { it.to == "lc4_rec@corp.example.com" }).hasSize(1)
        assertThat(remindersSent(requestId)).isEqualTo(1)
    }

    // ---- expiration ----

    @Test
    fun `past-due request expires with tokens revoked`() {
        val requestId = sendRequest("lc5_requester@example.com", "lc5_rec@corp.example.com")
        val invitationToken = Regex("/invitations/([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == "lc5_rec@corp.example.com" }.textBody)!!.groupValues[1]

        val rr = REFERENCE_REQUEST
        dsl.update(rr)
            .set(rr.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
            .where(rr.ID.eq(UUID.fromString(requestId)))
            .execute()

        task.run()

        assertThat(status(requestId)).isEqualTo("EXPIRED")
        val it = INVITATION_TOKEN
        val tokenRow = dsl.selectFrom(it).where(it.REQUEST_ID.eq(UUID.fromString(requestId))).fetchOne()!!
        assertThat(tokenRow.revokedAt).isNotNull()
        assertThat(
            rest.exchange(
                "/api/v1/invitations/$invitationToken", HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders()), Map::class.java,
            ).statusCode,
        ).isEqualTo(HttpStatus.NOT_FOUND)

        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("REFERENCE_REQUEST_EXPIRED")

        // Idempotency: second run leaves EXPIRED untouched.
        task.run()
        assertThat(status(requestId)).isEqualTo("EXPIRED")
    }
}
