package com.verifolio.privacy

import com.verifolio.files.infrastructure.S3StorageAdapter
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.jooq.tables.references.FILE_OBJECT
import com.verifolio.platform.VerifolioProperties
import com.verifolio.privacy.application.DataSubjectRequestService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/** DSR EXPORT executor: account-holder full package + recommender thin package. */
class ExportExecutorIntegrationTest : PrivacyFlowSupport() {

    @Autowired internal lateinit var service: DataSubjectRequestService
    @Autowired internal lateinit var storage: S3StorageAdapter
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var props: VerifolioProperties

    private fun storedExportJson(fileId: UUID): JsonNode {
        val fo = FILE_OBJECT
        val key = dsl.select(fo.STORAGE_KEY).from(fo).where(fo.ID.eq(fileId)).fetchOne(fo.STORAGE_KEY)!!
        return objectMapper.readTree(storage.getBytes(key))
    }

    @Test
    fun `account-holder EXPORT assembles the full package, stores it, emails a link, and executes`() {
        val owner = "export_owner@example.com"
        val recommender = "export_rec@corp.example.com"
        // Seeds: a contact, a reference request, a locked document version, and consent records.
        val completed = driveToCompleted(owner, recommender)

        // Owner submits an EXPORT DSR (user-scoped, RECEIVED, verified at creation).
        val ownerCookie = login(owner)
        val ownerXsrf = xsrf(ownerCookie)
        mail.sent.clear()
        val submit = rest.exchange(
            "/api/v1/privacy/data-subject-requests", HttpMethod.POST,
            HttpEntity(mapOf("type" to "EXPORT"), authHeaders(ownerCookie, ownerXsrf)),
            Map::class.java,
        )
        assertThat(submit.statusCode).isEqualTo(HttpStatus.CREATED)
        val dsrId = UUID.fromString(submit.body!!["id"] as String)

        // Admin executes.
        val adminActorId = UUID.randomUUID().toString()
        service.execute(dsrId, adminActorId)

        // A DATA_EXPORT FileObject was created and recorded on the DSR.
        val d = DATA_SUBJECT_REQUEST
        val row = dsl.selectFrom(d).where(d.ID.eq(dsrId)).fetchOne()!!
        assertThat(row.status).isEqualTo("EXECUTED")
        val fileId = row.exportFileId
        assertThat(fileId).isNotNull()
        val fo = FILE_OBJECT
        assertThat(dsl.select(fo.PURPOSE).from(fo).where(fo.ID.eq(fileId)).fetchOne(fo.PURPOSE))
            .isEqualTo("DATA_EXPORT")

        // The subject received an export mail carrying a presigned link (not the default 5m TTL).
        val exportMail = mail.sent.last { it.to == owner && it.subject == "Your Verifolio data export" }
        assertThat(exportMail.textBody).contains("http")
        assertThat(exportMail.textBody).contains("X-Amz-") // presigned query params

        // The stored JSON contains the seeded metadata across every section, and NO letter text.
        val json = storedExportJson(fileId!!)
        assertThat(json["subjectType"].asString()).isEqualTo("ACCOUNT_HOLDER")
        assertThat(json["account"]["email"].asString()).isEqualTo(owner)
        assertThat(json["profile"]["displayName"].asString()).isEqualTo("export_owner")
        assertThat(json["contacts"].values().map { it["email"].asString() }).contains(recommender)
        assertThat(json["referenceRequests"].values().map { it["id"].asString() })
            .contains(completed.requestId.toString())
        assertThat(json["documents"].isEmpty).isFalse()
        assertThat(json["documents"][0]["versions"][0]["sha256"].asString()).isNotBlank()
        assertThat(json["consents"].isEmpty).isFalse()
        assertThat(json["dataSubjectRequests"].values().map { it["type"].asString() }).contains("EXPORT")
        // No response letter / answer content anywhere in the package.
        val raw = String(storage.getBytes(dsl.select(fo.STORAGE_KEY).from(fo).where(fo.ID.eq(fileId)).fetchOne(fo.STORAGE_KEY)!!))
        assertThat(raw).doesNotContain("An excellent colleague")
        assertThat(raw).doesNotContain("Great work")

        // DATA_EXPORTED audited (ADMIN actor, ids only) and DSR EXECUTED audited.
        val exported = dsl.selectFrom(AUDIT_EVENT)
            .where(
                AUDIT_EVENT.ACTION.eq("DATA_EXPORTED")
                    .and(AUDIT_EVENT.ENTITY_ID.eq(dsrId.toString())),
            )
            .fetchOne()!!
        assertThat(exported.actorType).isEqualTo("ADMIN")
        assertThat(exported.actorId).isEqualTo(adminActorId)
        assertThat(exported.metadata.toString()).contains(fileId.toString())
        assertThat(exported.metadata.toString()).contains("ACCOUNT_HOLDER")
        assertThat(exported.metadata.toString()).doesNotContain(owner)

        assertThat(
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ACTION.eq("DATA_SUBJECT_REQUEST_EXECUTED")
                    .and(AUDIT_EVENT.ENTITY_ID.eq(dsrId.toString())),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `recommender EXPORT produces a thin package — no account, profile, or contacts`() {
        val owner = "thin_owner@example.com"
        val recommender = "thin_rec@corp.example.com"
        val completed = driveToCompleted(owner, recommender)

        // A verified recommender-scoped EXPORT DSR (mirrors the account-less channel post-verify).
        val dsrId = UUID.randomUUID()
        val d = DATA_SUBJECT_REQUEST
        val now = OffsetDateTime.now()
        dsl.insertInto(d)
            .set(d.ID, dsrId)
            .set(d.TYPE, "EXPORT")
            .set(d.STATUS, "RECEIVED")
            .set(d.REGION, props.region)
            .set(d.SUBJECT_EMAIL, recommender)
            .set(d.RECOMMENDER_CONTACT_ID, completed.contactId)
            .set(d.VERIFIED_AT, now)
            .set(d.DUE_AT, now.plus(props.privacy.sla))
            .execute()

        mail.sent.clear()
        service.execute(dsrId)

        val row = dsl.selectFrom(d).where(d.ID.eq(dsrId)).fetchOne()!!
        assertThat(row.status).isEqualTo("EXECUTED")
        val fileId = row.exportFileId!!

        val json = storedExportJson(fileId)
        assertThat(json["subjectType"].asString()).isEqualTo("RECOMMENDER")
        // Thin: account/profile/contacts/documents omitted.
        assertThat(json.has("account")).isFalse()
        assertThat(json.has("profile")).isFalse()
        assertThat(json.has("contacts")).isFalse()
        assertThat(json.has("documents")).isFalse()
        // Present: surviving request + consent metadata, and the DSR history.
        assertThat(json["referenceRequests"].values().map { it["id"].asString() })
            .contains(completed.requestId.toString())
        assertThat(json["consents"].isEmpty).isFalse()
        assertThat(json["dataSubjectRequests"].isEmpty).isFalse()

        // SYSTEM actor (no admin id on the account-less path).
        val exported = dsl.selectFrom(AUDIT_EVENT)
            .where(AUDIT_EVENT.ACTION.eq("DATA_EXPORTED").and(AUDIT_EVENT.ENTITY_ID.eq(dsrId.toString())))
            .fetchOne()!!
        assertThat(exported.actorType).isEqualTo("SYSTEM")
    }
}
