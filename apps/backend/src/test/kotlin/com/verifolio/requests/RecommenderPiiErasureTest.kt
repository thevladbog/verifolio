package com.verifolio.requests

import com.verifolio.files.infrastructure.S3StorageAdapter
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.EMAIL_CONFIRMATION_CODE
import com.verifolio.jooq.tables.references.FILE_OBJECT
import com.verifolio.jooq.tables.references.INVITATION_TOKEN
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import com.verifolio.jooq.tables.references.RECOMMENDER_SESSION
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.jooq.tables.references.REFERENCE_RESPONSE
import com.verifolio.jooq.tables.references.RESPONSE_UPLOAD
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
import org.jooq.DSLContext
import java.util.UUID

/**
 * Drives a request through the recommender flow to a submitted state with one confirmed
 * (unattached) upload, then exercises [RecommenderPiiErasure.eraseForRequest] against every
 * row of the erasure matrix (privacy/DSR design), plus idempotency and the owner DTO field.
 */
@Import(RecordingMailConfig::class)
class RecommenderPiiErasureTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var erasure: RecommenderPiiErasure
    @Autowired internal lateinit var storage: S3StorageAdapter

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    // ---- requester-side helpers (same patterns as ResponseReviewIntegrationTest) ----

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
        assertThat(contactResponse.statusCode).isEqualTo(HttpStatus.CREATED)

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
                    "recommenderContactId" to (contactResponse.body!!["id"] as String),
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
        val response = rest.exchange(
            "/api/v1/recommender/consent", HttpMethod.POST,
            HttpEntity(mapOf("accepted" to true), authHeaders(cookie, recommenderXsrf(cookie))),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    private val pdfBytes = "%PDF-1.7 erasure test scan content".toByteArray(Charsets.US_ASCII)

    /** Creates a READY (unattached) SCAN upload; returns the file_object id. */
    private fun createConfirmedUpload(cookie: String): UUID {
        val created = rest.exchange(
            "/api/v1/recommender/uploads", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "kind" to "SCAN", "filename" to "scan.pdf", "mimeType" to "application/pdf",
                    "sizeBytes" to pdfBytes.size.toLong(), "sharedPublicly" to false,
                ),
                authHeaders(cookie, recommenderXsrf(cookie)),
            ),
            Map::class.java,
        )
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val uploadId = created.body!!["uploadId"] as String
        val uploadUrl = created.body!!["uploadUrl"] as String

        val client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build()
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

        val ru = RESPONSE_UPLOAD
        return dsl.select(ru.FILE_OBJECT_ID).from(ru)
            .where(ru.ID.eq(UUID.fromString(uploadId))).fetchOne(ru.FILE_OBJECT_ID)!!
    }

    private fun submitResponse(cookie: String) {
        val submit = rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "approvedLetterText" to "An excellent colleague.",
                    "confirmationText" to "I confirm the information is accurate",
                    "recipientConfirmed" to true,
                    "relationshipConfirmed" to true,
                    "answersJson" to mapOf("q1" to "Great work"),
                ),
                authHeaders(cookie, recommenderXsrf(cookie)),
            ),
            Map::class.java,
        )
        assertThat(submit.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    /** Full drive to a submitted response with one confirmed upload; returns request id + file id. */
    private fun driveToSubmittedWithUpload(requester: String, recommender: String): Pair<UUID, UUID> {
        val (rawToken, requestId) = sendInvitation(requester, recommender)
        rest.getForEntity("/api/v1/invitations/$rawToken", Map::class.java)
        val recCookie = confirmEmail(rawToken, recommender)
        acceptConsent(recCookie)
        val fileId = createConfirmedUpload(recCookie)
        submitResponse(recCookie)
        return UUID.fromString(requestId) to fileId
    }

    // ---- tests ----

    @Test
    fun `erasure clears every matrix row and leaves contact and consents intact`() {
        val (requestId, fileId) = driveToSubmittedWithUpload(
            "erase_owner@example.com", "erase_rec@corp.example.com",
        )

        val rr = REFERENCE_REQUEST
        val contactId = dsl.select(rr.RECOMMENDER_CONTACT_ID).from(rr)
            .where(rr.ID.eq(requestId)).fetchOne(rr.RECOMMENDER_CONTACT_ID)!!
        val storageKey = dsl.select(FILE_OBJECT.STORAGE_KEY).from(FILE_OBJECT)
            .where(FILE_OBJECT.ID.eq(fileId)).fetchOne(FILE_OBJECT.STORAGE_KEY)!!
        // Sanity: the object exists and the pre-conditions hold before erasure.
        assertThat(storage.headSize(storageKey)).isNotNull()
        val consentCountBefore = dsl.fetchCount(
            CONSENT_RECORD, CONSENT_RECORD.REFERENCE_REQUEST_ID.eq(requestId),
        )
        assertThat(consentCountBefore).isGreaterThan(0)

        val summary = erasure.eraseForRequest(requestId)

        assertThat(summary.requestId).isEqualTo(requestId)
        assertThat(summary.responsesDeleted).isEqualTo(1)
        assertThat(summary.uploadsDeleted).isEqualTo(1)
        assertThat(summary.tokensScrubbed).isGreaterThanOrEqualTo(1)
        assertThat(summary.sessionsDeleted).isGreaterThanOrEqualTo(1)

        // Snapshot columns nulled + marker set.
        val request = dsl.selectFrom(rr).where(rr.ID.eq(requestId)).fetchOne()!!
        assertThat(request.recommenderName).isNull()
        assertThat(request.recommenderEmail).isNull()
        assertThat(request.recommenderPiiErasedAt).isNotNull()

        // reference_response rows gone.
        assertThat(dsl.fetchCount(REFERENCE_RESPONSE, REFERENCE_RESPONSE.REQUEST_ID.eq(requestId))).isZero()

        // Unattached upload row gone; file_object DELETED; the MinIO object is absent.
        assertThat(dsl.fetchCount(RESPONSE_UPLOAD, RESPONSE_UPLOAD.REQUEST_ID.eq(requestId))).isZero()
        val fileStatus = dsl.select(FILE_OBJECT.STATUS, FILE_OBJECT.DELETED_AT).from(FILE_OBJECT)
            .where(FILE_OBJECT.ID.eq(fileId)).fetchOne()!!
        assertThat(fileStatus.value1()).isEqualTo("DELETED")
        assertThat(fileStatus.value2()).isNotNull()
        assertThat(storage.headSize(storageKey)).isNull()

        // invitation_token email nulled; confirmation codes for the request's tokens gone.
        assertThat(
            dsl.fetchCount(
                INVITATION_TOKEN,
                INVITATION_TOKEN.REQUEST_ID.eq(requestId).and(INVITATION_TOKEN.RECOMMENDER_EMAIL.isNotNull),
            ),
        ).isZero()
        assertThat(
            dsl.fetchCount(
                EMAIL_CONFIRMATION_CODE,
                EMAIL_CONFIRMATION_CODE.INVITATION_TOKEN_ID.`in`(
                    dsl.select(INVITATION_TOKEN.ID).from(INVITATION_TOKEN)
                        .where(INVITATION_TOKEN.REQUEST_ID.eq(requestId)),
                ),
            ),
        ).isZero()

        // recommender_session rows gone.
        assertThat(dsl.fetchCount(RECOMMENDER_SESSION, RECOMMENDER_SESSION.REQUEST_ID.eq(requestId))).isZero()

        // recommender_contact untouched (name + email preserved).
        val contact = dsl.selectFrom(RECOMMENDER_CONTACT).where(RECOMMENDER_CONTACT.ID.eq(contactId)).fetchOne()!!
        assertThat(contact.email).isEqualTo("erase_rec@corp.example.com")
        assertThat(contact.name).isEqualTo("Rec Ommender")

        // consent_record rows retained.
        assertThat(dsl.fetchCount(CONSENT_RECORD, CONSENT_RECORD.REFERENCE_REQUEST_ID.eq(requestId)))
            .isEqualTo(consentCountBefore)

        // Audit: RECOMMENDER_PII_ERASED by SYSTEM with the counts.
        val auditMeta = dsl.select(AUDIT_EVENT.METADATA, AUDIT_EVENT.ACTOR_TYPE).from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ACTION.eq("RECOMMENDER_PII_ERASED").and(AUDIT_EVENT.ENTITY_ID.eq(requestId.toString())))
            .fetchOne()!!
        assertThat(auditMeta.value2()).isEqualTo("SYSTEM")
        val metaJson = auditMeta.value1()!!.data()
        assertThat(metaJson).contains("responsesDeleted", "uploadsDeleted", "tokensScrubbed", "sessionsDeleted")
        assertThat(metaJson).contains(requestId.toString())
    }

    @Test
    fun `second erasure is a zero-summary no-op`() {
        val (requestId, _) = driveToSubmittedWithUpload(
            "erase_idem_owner@example.com", "erase_idem_rec@corp.example.com",
        )
        erasure.eraseForRequest(requestId)

        val rr = REFERENCE_REQUEST
        val erasedAt = dsl.select(rr.RECOMMENDER_PII_ERASED_AT).from(rr)
            .where(rr.ID.eq(requestId)).fetchOne(rr.RECOMMENDER_PII_ERASED_AT)
        val auditCountBefore = dsl.fetchCount(
            AUDIT_EVENT,
            AUDIT_EVENT.ACTION.eq("RECOMMENDER_PII_ERASED").and(AUDIT_EVENT.ENTITY_ID.eq(requestId.toString())),
        )

        val second = erasure.eraseForRequest(requestId)
        assertThat(second.responsesDeleted).isZero()
        assertThat(second.uploadsDeleted).isZero()
        assertThat(second.tokensScrubbed).isZero()
        assertThat(second.sessionsDeleted).isZero()

        // Nothing changed: marker preserved, no additional audit row.
        assertThat(
            dsl.select(rr.RECOMMENDER_PII_ERASED_AT).from(rr).where(rr.ID.eq(requestId))
                .fetchOne(rr.RECOMMENDER_PII_ERASED_AT),
        ).isEqualTo(erasedAt)
        assertThat(
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ACTION.eq("RECOMMENDER_PII_ERASED").and(AUDIT_EVENT.ENTITY_ID.eq(requestId.toString())),
            ),
        ).isEqualTo(auditCountBefore)
    }

    @Test
    fun `owner GET exposes recommenderPiiErasedAt after erasure`() {
        val (requestId, _) = driveToSubmittedWithUpload(
            "erase_owner_get@example.com", "erase_rec_get@corp.example.com",
        )
        erasure.eraseForRequest(requestId)

        val cookie = login("erase_owner_get@example.com")
        val response = rest.exchange(
            "/api/v1/reference-requests/$requestId", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["recommenderPiiErasedAt"]).isNotNull()
    }
}
