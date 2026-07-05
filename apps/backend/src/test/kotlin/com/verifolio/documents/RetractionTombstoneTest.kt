package com.verifolio.documents

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_ATTACHMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import com.verifolio.jooq.tables.references.FILE_OBJECT
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

/**
 * Retraction and tombstoning of a locked version + the public-page states for both.
 * Drives the canonical flow to COMPLETED (locked version + a shared attachment + a share
 * link) via the public API, then exercises the two documents public APIs directly.
 */
@Import(RecordingMailConfig::class)
class RetractionTombstoneTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var retraction: DocumentRetraction
    @Autowired lateinit var tombstone: DocumentTombstone

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    // ---- flow helpers (mirrors PublicVerificationIntegrationTest) ----

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

    /** Requester creates+sends, recommender confirms+consents+uploads+submits, requester accepts. */
    private fun completeDocument(requesterEmail: String, recommenderEmail: String): Pair<String, String> {
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
                    "purpose" to "Employment verification", "verbalConsentAttested" to true,
                ),
                authHeaders(cookie, xsrfToken),
            ),
            Map::class.java,
        ).body!!["id"] as String

        rest.exchange(
            "/api/v1/reference-requests/$requestId/send", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)), Map::class.java,
        )
        val invToken = Regex("/invitations/([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == recommenderEmail }.textBody)!!.groupValues[1]

        rest.exchange("/api/v1/invitations/$invToken", HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), Map::class.java)
        rest.exchange("/api/v1/invitations/$invToken/email-confirmations", HttpMethod.POST, HttpEntity<Void>(HttpHeaders()), Map::class.java)
        val code = Regex("Code: (\\d{6})").find(mail.sent.last { it.to == recommenderEmail }.textBody)!!.groupValues[1]
        val recCookie = rest.exchange(
            "/api/v1/invitations/$invToken/confirm-email", HttpMethod.POST,
            HttpEntity(mapOf("code" to code), HttpHeaders().apply { set(HttpHeaders.CONTENT_TYPE, "application/json") }),
            Map::class.java,
        ).headers[HttpHeaders.SET_COOKIE]!!.first { it.startsWith("verifolio_recommender_session=") }.substringBefore(";")

        val recXsrf = rest.exchange(
            "/api/v1/recommender/request", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, recCookie) }), Map::class.java,
        ).headers[HttpHeaders.SET_COOKIE]?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")

        rest.exchange(
            "/api/v1/recommender/consent", HttpMethod.POST,
            HttpEntity(mapOf("accepted" to true), authHeaders(recCookie, recXsrf)), Map::class.java,
        )

        // Upload a shared scan so the locked version has an attachment to tombstone.
        val scanBytes = "%PDF-1.7 uploaded evidence scan".toByteArray(Charsets.US_ASCII)
        val created = rest.exchange(
            "/api/v1/recommender/uploads", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "kind" to "SCAN", "filename" to "letterhead-scan.pdf",
                    "mimeType" to "application/pdf", "sizeBytes" to scanBytes.size,
                    "sharedPublicly" to true,
                ),
                authHeaders(recCookie, recXsrf),
            ),
            Map::class.java,
        )
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val put = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build()
            .send(
                java.net.http.HttpRequest.newBuilder(java.net.URI(created.body!!["uploadUrl"] as String))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Content-Type", "application/pdf")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(scanBytes))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.discarding(),
            ).statusCode()
        assertThat(put).isEqualTo(200)
        rest.exchange(
            "/api/v1/recommender/uploads/${created.body!!["uploadId"]}/confirm", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(recCookie, recXsrf)), Map::class.java,
        )

        rest.exchange(
            "/api/v1/recommender/responses", HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "approvedLetterText" to "A reliable and skilled professional.",
                    "recipientConfirmed" to true, "relationshipConfirmed" to true,
                ),
                authHeaders(recCookie, recXsrf),
            ),
            Map::class.java,
        )

        val accept = rest.exchange(
            "/api/v1/reference-requests/$requestId/accept", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrf(cookie))), Map::class.java,
        )
        assertThat(accept.statusCode).isEqualTo(HttpStatus.OK)
        return cookie to (accept.body!!["documentId"] as String)
    }

    private fun createShareToken(cookie: String, documentId: String): String {
        val response = rest.exchange(
            "/api/v1/documents/$documentId/share-links", HttpMethod.POST,
            HttpEntity(mapOf<String, Any>(), authHeaders(cookie, xsrf(cookie))),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return (response.body!!["url"] as String).substringAfterLast("/verify/")
    }

    private fun page(rawToken: String) = rest.exchange(
        "/api/v1/verification-pages/$rawToken", HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders()), Map::class.java,
    )

    private fun auditCount(action: String, entityId: String): Int = dsl.fetchCount(
        dsl.selectFrom(AUDIT_EVENT).where(AUDIT_EVENT.ACTION.eq(action).and(AUDIT_EVENT.ENTITY_ID.eq(entityId))),
    )

    private fun requestIdOf(documentId: String): UUID =
        dsl.select(DOCUMENT.REQUEST_ID).from(DOCUMENT)
            .where(DOCUMENT.ID.eq(UUID.fromString(documentId))).fetchOne(DOCUMENT.REQUEST_ID)!!

    private fun versionIdOf(documentId: String): UUID =
        dsl.select(DOCUMENT.CURRENT_VERSION_ID).from(DOCUMENT)
            .where(DOCUMENT.ID.eq(UUID.fromString(documentId))).fetchOne(DOCUMENT.CURRENT_VERSION_ID)!!

    // ---- retraction ----

    @Test
    fun `retraction stamps retracted_at, keeps content, audits, and surfaces on the public page`() {
        val (cookie, documentId) = completeDocument("retract_owner@example.com", "retract_rec@corp.example.com")
        val rawToken = createShareToken(cookie, documentId)
        val requestId = requestIdOf(documentId)
        val versionId = versionIdOf(documentId)

        val dv = DOCUMENT_VERSION
        val before = dsl.selectFrom(dv).where(dv.ID.eq(versionId)).fetchOne()!!
        assertThat(before.retractedAt).isNull()

        val affected = retraction.markRetracted(requestId)
        assertThat(affected).isEqualTo(1)

        val after = dsl.selectFrom(dv).where(dv.ID.eq(versionId)).fetchOne()!!
        assertThat(after.retractedAt).isNotNull()
        // Retraction is NOT deletion: content and integrity anchors are untouched.
        assertThat(after.contentJson).isNotNull()
        assertThat(after.renderedHtml).isNotNull()
        assertThat(after.status).isEqualTo("LOCKED")
        assertThat(after.sha256Hash).isEqualTo(before.sha256Hash)

        assertThat(auditCount("RECOMMENDATION_RETRACTED", requestId.toString())).isEqualTo(1)

        // Public page: full data + retractedAt; still ACTIVE, PDF still downloadable.
        val response = page(rawToken)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["status"]).isEqualTo("ACTIVE")
        assertThat(body["recommender"]).isNotNull()
        @Suppress("UNCHECKED_CAST")
        val version = body["version"] as Map<String, Any?>
        assertThat(version["retractedAt"]).isNotNull()
        @Suppress("UNCHECKED_CAST")
        val badges = body["badges"] as List<Map<String, Any>>
        assertThat(badges).isNotEmpty()
        val download = rest.exchange(
            "/api/v1/verification-pages/$rawToken/download-url", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()), Map::class.java,
        )
        assertThat(download.statusCode).isEqualTo(HttpStatus.OK)

        // Idempotent: second call affects nothing and emits no further audit.
        assertThat(retraction.markRetracted(requestId)).isEqualTo(0)
        assertThat(auditCount("RECOMMENDATION_RETRACTED", requestId.toString())).isEqualTo(1)
    }

    // ---- tombstoning ----

    @Test
    fun `tombstoning nulls content, retains integrity anchors, deletes objects, and audits`() {
        val (cookie, documentId) = completeDocument("tomb_owner@example.com", "tomb_rec@corp.example.com")
        val rawToken = createShareToken(cookie, documentId)
        val versionId = versionIdOf(documentId)

        val dv = DOCUMENT_VERSION
        val before = dsl.selectFrom(dv).where(dv.ID.eq(versionId)).fetchOne()!!
        val pdfFileId = before.pdfFileId!!
        val da = DOCUMENT_ATTACHMENT
        val attachmentFileIds = dsl.select(da.FILE_OBJECT_ID).from(da)
            .where(da.DOCUMENT_VERSION_ID.eq(versionId)).fetch(da.FILE_OBJECT_ID).filterNotNull()
        assertThat(attachmentFileIds).isNotEmpty()

        // Prove the PDF is actually served from S3 before tombstoning, then that the same
        // presigned URL 404s after (object physically deleted).
        val preUrl = rest.exchange(
            "/api/v1/verification-pages/$rawToken/download-url", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()), Map::class.java,
        ).body!!["url"] as String
        assertThat(java.net.URI(preUrl).toURL().readBytes()).isNotEmpty()

        tombstone.tombstone(versionId)

        val after = dsl.selectFrom(dv).where(dv.ID.eq(versionId)).fetchOne()!!
        assertThat(after.status).isEqualTo("TOMBSTONED")
        assertThat(after.tombstonedAt).isNotNull()
        assertThat(after.contentJson).isNull()
        assertThat(after.renderedHtml).isNull()
        // NON-NEGOTIABLE: the integrity anchors survive tombstoning.
        assertThat(after.sha256Hash).isEqualTo(before.sha256Hash)
        assertThat(after.versionNumber).isEqualTo(before.versionNumber)
        assertThat(after.lockedAt).isEqualTo(before.lockedAt)

        // Generated PDF + attachment file objects physically deleted (files module).
        val fo = FILE_OBJECT
        assertThat(dsl.select(fo.STATUS).from(fo).where(fo.ID.eq(pdfFileId)).fetchOne(fo.STATUS)).isEqualTo("DELETED")
        attachmentFileIds.forEach {
            assertThat(dsl.select(fo.STATUS).from(fo).where(fo.ID.eq(it)).fetchOne(fo.STATUS)).isEqualTo("DELETED")
        }
        // The S3 object is gone: the previously valid presigned GET now fails.
        val conn = java.net.URI(preUrl).toURL().openConnection() as java.net.HttpURLConnection
        assertThat(conn.responseCode).isNotEqualTo(200)

        assertThat(auditCount("DOCUMENT_VERSION_TOMBSTONED", versionId.toString())).isEqualTo(1)

        // Public page collapses to the minimal notice shape.
        val response = page(rawToken)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["status"]).isEqualTo("TOMBSTONED")
        // notice is null: the client localizes the removed-content copy (verify.removedBody).
        assertThat(body["notice"]).isNull()
        assertThat(body["recipient"]).isNull()
        assertThat(body["recommender"]).isNull()
        assertThat(body["version"]).isNull()
        @Suppress("UNCHECKED_CAST")
        assertThat(body["badges"] as List<Any>).isEmpty()
        @Suppress("UNCHECKED_CAST")
        assertThat(body["downloads"] as List<Any>).isEmpty()

        // Download endpoints 404 for a tombstoned version.
        assertThat(
            rest.exchange(
                "/api/v1/verification-pages/$rawToken/download-url", HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders()), Map::class.java,
            ).statusCode,
        ).isEqualTo(HttpStatus.NOT_FOUND)

        // Idempotent: a second tombstone is a no-op — no new audit, content still null.
        tombstone.tombstone(versionId)
        assertThat(auditCount("DOCUMENT_VERSION_TOMBSTONED", versionId.toString())).isEqualTo(1)
        val stillGone = dsl.selectFrom(dv).where(dv.ID.eq(versionId)).fetchOne()!!
        assertThat(stillGone.contentJson).isNull()
        assertThat(stillGone.status).isEqualTo("TOMBSTONED")
    }
}
