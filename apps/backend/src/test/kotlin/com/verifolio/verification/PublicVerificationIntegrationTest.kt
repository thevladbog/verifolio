package com.verifolio.verification

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.SHARE_LINK
import com.verifolio.jooq.tables.references.VERIFICATION_SIGNAL
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
import java.time.OffsetDateTime
import java.util.UUID

@Import(RecordingMailConfig::class)
class PublicVerificationIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var context: org.springframework.context.ApplicationContext

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    // ---- helpers (established patterns from the requests test suites) ----

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
     * Full API journey: requester creates+sends, recommender confirms+consents+submits,
     * requester accepts. Returns (requesterCookie, documentId).
     */
    private fun completeDocument(
        requesterEmail: String,
        recommenderEmail: String,
        /** null = no upload; true/false = upload a scan with the given sharing toggle. */
        uploadScanShared: Boolean? = null,
    ): Pair<String, String> {
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

        if (uploadScanShared != null) {
            val scanBytes = "%PDF-1.7 uploaded evidence scan".toByteArray(Charsets.US_ASCII)
            val created = rest.exchange(
                "/api/v1/recommender/uploads", HttpMethod.POST,
                HttpEntity(
                    mapOf(
                        "kind" to "SCAN", "filename" to "letterhead-scan.pdf",
                        "mimeType" to "application/pdf", "sizeBytes" to scanBytes.size,
                        "sharedPublicly" to uploadScanShared,
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
        }

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

    private fun createLink(cookie: String, documentId: String, expiresInDays: Int? = null): Map<*, *> {
        val body = expiresInDays?.let { mapOf("expiresInDays" to it) } ?: mapOf<String, Any>()
        val response = rest.exchange(
            "/api/v1/documents/$documentId/share-links", HttpMethod.POST,
            HttpEntity(body, authHeaders(cookie, xsrf(cookie))),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!
    }

    private fun rawTokenFrom(created: Map<*, *>): String =
        (created["url"] as String).substringAfterLast("/verify/")

    private fun auditActions(): List<String?> =
        dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)

    private fun page(rawToken: String) = rest.exchange(
        "/api/v1/verification-pages/$rawToken", HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders()),
        Map::class.java,
    )

    // ---- tests ----

    @Test
    fun `share link creation returns the raw url once and enables the publication signal`() {
        val (cookie, documentId) = completeDocument("share_owner@example.com", "share_rec@corp.example.com")
        val created = createLink(cookie, documentId, expiresInDays = 30)

        assertThat(created["url"] as String).contains("/verify/")
        assertThat(created["versionNumber"]).isEqualTo(1)
        assertThat(created["expiresAt"]).isNotNull()

        val linkId = UUID.fromString(created["id"] as String)
        val vs = VERIFICATION_SIGNAL
        val signal = dsl.selectFrom(vs)
            .where(vs.ENTITY_TYPE.eq("SHARE_LINK").and(vs.ENTITY_ID.eq(linkId)))
            .fetchOne()!!
        assertThat(signal.signalType).isEqualTo("PUBLIC_VERIFICATION_ENABLED")
        assertThat(signal.status).isEqualTo("VERIFIED")

        // The list endpoint never returns tokens.
        val list = rest.exchange(
            "/api/v1/documents/$documentId/share-links", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val items = list.body!!["items"] as List<Map<String, Any>>
        assertThat(items).hasSize(1)
        assertThat(items.first().keys).doesNotContain("url", "token")

        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("SHARE_LINK_CREATED")
    }

    @Test
    fun `public page renders all sections with the trust summary`() {
        val (cookie, documentId) = completeDocument("page_owner@example.com", "page_rec@corp.example.com")
        val rawToken = rawTokenFrom(createLink(cookie, documentId))

        val response = page(rawToken)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!

        @Suppress("UNCHECKED_CAST")
        val header = body["header"] as Map<String, Any>
        assertThat(header["documentType"]).isEqualTo("REFERENCE_LETTER")
        assertThat(header["verificationId"]).isNotNull()
        assertThat(header["lastVerifiedAt"]).isNotNull()

        @Suppress("UNCHECKED_CAST")
        val recommender = body["recommender"] as Map<String, Any>
        assertThat(recommender["name"]).isEqualTo("Rec Ommender")
        assertThat(recommender["nameSource"]).isEqualTo("provided-by-requester")
        assertThat(recommender["relationshipType"]).isEqualTo("MANAGER")
        assertThat(recommender["relationshipSource"]).isEqualTo("confirmed-by-recommender")

        @Suppress("UNCHECKED_CAST")
        val badges = body["badges"] as List<Map<String, Any>>
        assertThat(badges.map { it["signalType"] }).containsExactlyInAnyOrder(
            "RECIPIENT_CONFIRMED", "RECOMMENDER_RELATIONSHIP_CONFIRMED", "EMAIL_CONFIRMED",
            "CORPORATE_DOMAIN_CONFIRMED", "VERSION_LOCKED", "DOCUMENT_HASH_LOCKED",
            "PUBLIC_VERIFICATION_ENABLED",
        )
        assertThat(badges).allSatisfy { assertThat(it["status"]).isEqualTo("VERIFIED") }

        @Suppress("UNCHECKED_CAST")
        val summary = body["trustSummary"] as Map<String, Int>
        assertThat(summary["identity"]).isEqualTo(2)
        assertThat(summary["relationship"]).isEqualTo(2)
        assertThat(summary["documentIntegrity"]).isEqualTo(2)
        assertThat(summary["signature"]).isEqualTo(0)
        assertThat(summary["publication"]).isEqualTo(1)

        @Suppress("UNCHECKED_CAST")
        val version = body["version"] as Map<String, Any>
        assertThat(version["versionNumber"]).isEqualTo(1)
        assertThat(version["status"]).isEqualTo("LOCKED")
        assertThat(version["supersededByNewerVersion"]).isEqualTo(false)

        @Suppress("UNCHECKED_CAST")
        val timeline = body["timeline"] as List<Map<String, Any>>
        assertThat(timeline.map { it["event"] }).contains(
            "Request sent", "Response submitted", "Version locked", "Share link created",
        )

        assertThat(body["disclaimer"] as String).contains("does not independently guarantee")
        assertThat(body["privacyNotice"]).isNotNull()
    }

    @Test
    fun `revoked link returns 404 immediately and the signal is revoked`() {
        val (cookie, documentId) = completeDocument("revoke_owner@example.com", "revoke_rec@corp.example.com")
        val created = createLink(cookie, documentId)
        val rawToken = rawTokenFrom(created)
        val linkId = created["id"] as String

        assertThat(page(rawToken).statusCode).isEqualTo(HttpStatus.OK)

        val revoke = rest.exchange(
            "/api/v1/share-links/$linkId/revoke", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrf(cookie))),
            Map::class.java,
        )
        assertThat(revoke.statusCode).isEqualTo(HttpStatus.OK)

        assertThat(page(rawToken).statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        val vs = VERIFICATION_SIGNAL
        val signal = dsl.selectFrom(vs)
            .where(vs.ENTITY_TYPE.eq("SHARE_LINK").and(vs.ENTITY_ID.eq(UUID.fromString(linkId))))
            .fetchOne()!!
        assertThat(signal.status).isEqualTo("REVOKED")

        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("SHARE_LINK_REVOKED", "VERIFICATION_SIGNAL_UPDATED")

        // Double revoke → 409.
        val again = rest.exchange(
            "/api/v1/share-links/$linkId/revoke", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrf(cookie))),
            Map::class.java,
        )
        assertThat(again.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `expired link returns 404`() {
        val (cookie, documentId) = completeDocument("expire_owner@example.com", "expire_rec@corp.example.com")
        val created = createLink(cookie, documentId, expiresInDays = 1)
        val rawToken = rawTokenFrom(created)

        val sl = SHARE_LINK
        dsl.update(sl)
            .set(sl.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
            .where(sl.ID.eq(UUID.fromString(created["id"] as String)))
            .execute()

        assertThat(page(rawToken).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `unknown token returns 404`() {
        assertThat(page("definitely-not-a-real-token").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `foreign user cannot create or revoke links`() {
        val (cookie, documentId) = completeDocument("isolation_owner@example.com", "isolation_rec@corp.example.com")
        val created = createLink(cookie, documentId)

        val foreignCookie = login("isolation_intruder@example.com")
        val foreignXsrf = xsrf(foreignCookie)

        val create = rest.exchange(
            "/api/v1/documents/$documentId/share-links", HttpMethod.POST,
            HttpEntity(mapOf<String, Any>(), authHeaders(foreignCookie, foreignXsrf)),
            Map::class.java,
        )
        assertThat(create.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        val revoke = rest.exchange(
            "/api/v1/share-links/${created["id"]}/revoke", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(foreignCookie, foreignXsrf)),
            Map::class.java,
        )
        assertThat(revoke.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `public download url serves the pinned pdf and is fully audited`() {
        val (cookie, documentId) = completeDocument("dl_owner@example.com", "dl_rec@corp.example.com")
        val rawToken = rawTokenFrom(createLink(cookie, documentId))

        val response = rest.exchange(
            "/api/v1/verification-pages/$rawToken/download-url", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val url = response.body!!["url"] as String
        val bytes = java.net.URI(url).toURL().readBytes()
        assertThat(String(bytes.copyOfRange(0, 5), Charsets.US_ASCII)).isEqualTo("%PDF-")

        val fo = com.verifolio.jooq.tables.references.FILE_OBJECT
        val storedSha = dsl.select(fo.SHA256_HASH).from(fo)
            .orderBy(fo.CREATED_AT.desc()).limit(10)
            .fetch(fo.SHA256_HASH)
        val fetchedSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        assertThat(storedSha).contains(fetchedSha)

        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("PUBLIC_VERIFICATION_PAGE_DOWNLOAD")
    }

    @Test
    fun `consented attachment is publicly listed and downloadable`() {
        val (cookie, documentId) = completeDocument("attdl_owner@example.com", "attdl_rec@corp.example.com", uploadScanShared = true)
        val rawToken = rawTokenFrom(createLink(cookie, documentId))

        val pageResponse = page(rawToken)
        @Suppress("UNCHECKED_CAST")
        val downloads = pageResponse.body!!["downloads"] as List<Map<String, Any>>
        assertThat(downloads.first()["id"]).isEqualTo("generated-pdf")
        val scanDownload = downloads.first { it["kind"] == "SCAN" }
        assertThat(scanDownload["downloadable"]).isEqualTo(true)
        assertThat(scanDownload["filename"]).isEqualTo("letterhead-scan.pdf")

        // Badges include the scan signal.
        @Suppress("UNCHECKED_CAST")
        val badges = pageResponse.body!!["badges"] as List<Map<String, Any>>
        assertThat(badges.map { it["signalType"] }).contains("SCAN_ATTACHED")

        val attachmentId = scanDownload["id"] as String
        val link = rest.exchange(
            "/api/v1/verification-pages/$rawToken/attachments/$attachmentId/download-url", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )
        assertThat(link.statusCode).isEqualTo(HttpStatus.OK)
        val bytes = java.net.URI(link.body!!["url"] as String).toURL().readBytes()
        assertThat(String(bytes.copyOfRange(0, 5), Charsets.US_ASCII)).isEqualTo("%PDF-")
        assertThat(auditActions()).contains("PUBLIC_VERIFICATION_PAGE_DOWNLOAD", "FILE_DOWNLOAD_GRANTED")
    }

    @Test
    fun `unconsented attachment is listed without filename and not downloadable`() {
        val (cookie, documentId) = completeDocument("attnodl_owner@example.com", "attnodl_rec@corp.example.com", uploadScanShared = false)
        val rawToken = rawTokenFrom(createLink(cookie, documentId))

        val pageResponse = page(rawToken)
        @Suppress("UNCHECKED_CAST")
        val downloads = pageResponse.body!!["downloads"] as List<Map<String, Any>>
        val scanDownload = downloads.first { it["kind"] == "SCAN" }
        assertThat(scanDownload["downloadable"]).isEqualTo(false)
        assertThat(scanDownload["filename"]).isNull()

        val link = rest.exchange(
            "/api/v1/verification-pages/$rawToken/attachments/${scanDownload["id"]}/download-url", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )
        assertThat(link.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        // The negative sharing decision is recorded too (AGENTS.md: accept or decline
        // is always recorded).
        val cr = com.verifolio.jooq.tables.references.CONSENT_RECORD
        val ru = com.verifolio.jooq.tables.references.RESPONSE_UPLOAD
        val declined = dsl.select(cr.STATUS).from(cr)
            .join(ru).on(ru.CONSENT_RECORD_ID.eq(cr.ID))
            .where(cr.CONSENT_TYPE.eq("RECOMMENDER_PUBLIC_SHARING_CONSENT"))
            .orderBy(cr.ID.desc())
            .limit(5)
            .fetch(cr.STATUS)
        assertThat(declined).contains("DECLINED")
        assertThat(auditActions()).contains("CONSENT_DECLINED")
    }

    @Test
    fun `expired link signal is swept to EXPIRED while revoked stays REVOKED`() {
        val (cookie, documentId) = completeDocument("sweep_owner@example.com", "sweep_rec@corp.example.com")
        val expiredLink = createLink(cookie, documentId, expiresInDays = 1)
        val revokedLink = createLink(cookie, documentId)

        // Revoke one link; backdate the other's expiry.
        rest.exchange(
            "/api/v1/share-links/${revokedLink["id"]}/revoke", HttpMethod.POST,
            HttpEntity<Void>(authHeaders(cookie, xsrf(cookie))), Map::class.java,
        )
        val sl = SHARE_LINK
        dsl.update(sl)
            .set(sl.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
            .where(sl.ID.eq(UUID.fromString(expiredLink["id"] as String)))
            .execute()

        val task = context.getBeansOfType(com.verifolio.workflows.RecurringTask::class.java).values
            .first { it.name == "expired-share-link-signals" }
        task.run()

        val vs = VERIFICATION_SIGNAL
        fun signalStatus(linkId: String): String =
            dsl.select(vs.STATUS).from(vs)
                .where(vs.ENTITY_TYPE.eq("SHARE_LINK").and(vs.ENTITY_ID.eq(UUID.fromString(linkId))))
                .fetchOne(vs.STATUS)!!
        assertThat(signalStatus(expiredLink["id"] as String)).isEqualTo("EXPIRED")
        assertThat(signalStatus(revokedLink["id"] as String)).isEqualTo("REVOKED")

        // Isolate the sweep's own audit trail: the EXPIRED flip of THIS link's signal
        // (the earlier revoke also emits VERIFICATION_SIGNAL_UPDATED) + the mandatory
        // link-level SHARE_LINK_EXPIRED event.
        val expiredFlips = dsl.selectFrom(AUDIT_EVENT)
            .where(AUDIT_EVENT.ACTION.eq("VERIFICATION_SIGNAL_UPDATED"))
            .fetch()
            .filter {
                val meta = it.metadata!!.data()
                meta.contains("EXPIRED") && meta.contains(expiredLink["id"] as String)
            }
        assertThat(expiredFlips).hasSize(1)
        val linkExpiredEvents = dsl.fetchCount(
            dsl.selectFrom(AUDIT_EVENT).where(
                AUDIT_EVENT.ACTION.eq("SHARE_LINK_EXPIRED")
                    .and(AUDIT_EVENT.ENTITY_ID.eq(expiredLink["id"] as String)),
            ),
        )
        assertThat(linkExpiredEvents).isEqualTo(1)

        // Second sweep: no duplicate events.
        task.run()
        assertThat(
            dsl.fetchCount(
                dsl.selectFrom(AUDIT_EVENT).where(
                    AUDIT_EVENT.ACTION.eq("SHARE_LINK_EXPIRED")
                        .and(AUDIT_EVENT.ENTITY_ID.eq(expiredLink["id"] as String)),
                ),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `corporate badge exposes the verified organization name and provenance`() {
        // The SAP seed (V13) owns sap.com, so acceptance snapshots the verified-record
        // provenance into the CORPORATE_DOMAIN_CONFIRMED evidence (Task 3). The public page
        // surfaces the public organization name + source from that snapshot — no new lookup.
        val (cookie, documentId) = completeDocument("orgprov_owner@example.com", "hiring.mgr@sap.com")
        val rawToken = rawTokenFrom(createLink(cookie, documentId))

        @Suppress("UNCHECKED_CAST")
        val badges = page(rawToken).body!!["badges"] as List<Map<String, Any?>>
        val corporate = badges.first { it["signalType"] == "CORPORATE_DOMAIN_CONFIRMED" }
        assertThat(corporate["organizationName"]).isEqualTo("SAP SE")
        assertThat(corporate["organizationSource"]).isEqualTo("verified-record")

        // Non-corporate badges never carry an organization name.
        val emailBadge = badges.first { it["signalType"] == "EMAIL_CONFIRMED" }
        assertThat(emailBadge["organizationName"]).isNull()
        assertThat(emailBadge["organizationSource"]).isNull()
    }

    @Test
    fun `recommender-stated corporate badge carries no organization name`() {
        // corp.example.com is not a seeded verified org → the evidence stays recommender-stated,
        // so the public badge exposes no organization name and keeps the existing framing.
        val (cookie, documentId) = completeDocument("orgstated_owner@example.com", "rec@corp.example.com")
        val rawToken = rawTokenFrom(createLink(cookie, documentId))

        @Suppress("UNCHECKED_CAST")
        val badges = page(rawToken).body!!["badges"] as List<Map<String, Any?>>
        val corporate = badges.first { it["signalType"] == "CORPORATE_DOMAIN_CONFIRMED" }
        assertThat(corporate["organizationName"]).isNull()
        assertThat(corporate["organizationSource"]).isNull()
    }

    @Test
    fun `page views are audited at sample rate one`() {
        val (cookie, documentId) = completeDocument("view_owner@example.com", "view_rec@corp.example.com")
        val created = createLink(cookie, documentId)
        val rawToken = rawTokenFrom(created)
        val linkId = created["id"] as String

        repeat(3) { assertThat(page(rawToken).statusCode).isEqualTo(HttpStatus.OK) }

        val views = dsl.fetchCount(
            dsl.selectFrom(AUDIT_EVENT).where(
                AUDIT_EVENT.ACTION.eq("PUBLIC_VERIFICATION_PAGE_VIEWED")
                    .and(AUDIT_EVENT.ENTITY_ID.eq(linkId)),
            ),
        )
        assertThat(views).isEqualTo(3)
    }
}
