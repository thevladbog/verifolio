package com.verifolio.documents

import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

class DocumentExportIntegrationTest : IntegrationTest() {

    @Autowired lateinit var documentExport: DocumentExport
    @Autowired lateinit var dsl: DSLContext

    private fun seedProfile(): UUID {
        val userId = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, userId).set(ua.EMAIL, "doc-owner-${UUID.randomUUID()}@example.com")
            .set(ua.REGION, "EU").set(ua.STATUS, "ACTIVE").execute()
        val profileId = UUID.randomUUID()
        val pp = PERSON_PROFILE
        dsl.insertInto(pp)
            .set(pp.ID, profileId).set(pp.USER_ACCOUNT_ID, userId)
            .set(pp.DISPLAY_NAME, "Owner").set(pp.PREFERRED_LOCALE, "en").execute()
        return profileId
    }

    private fun seedDocument(ownerProfileId: UUID, type: String): UUID {
        val id = UUID.randomUUID()
        val d = DOCUMENT
        dsl.insertInto(d)
            .set(d.ID, id).set(d.OWNER_PROFILE_ID, ownerProfileId).set(d.TYPE, type).execute()
        return id
    }

    private fun seedVersion(documentId: UUID, versionNumber: Int, sha: String, status: String) {
        val dv = DOCUMENT_VERSION
        dsl.insertInto(dv)
            .set(dv.ID, UUID.randomUUID())
            .set(dv.DOCUMENT_ID, documentId)
            .set(dv.VERSION_NUMBER, versionNumber)
            .set(dv.SHA256_HASH, sha)
            .set(dv.STATUS, status)
            .set(dv.LOCKED_AT, OffsetDateTime.now())
            .execute()
    }

    @Test
    fun `returns the owner's documents with version metadata, excluding other owners`() {
        val owner = seedProfile()
        val other = seedProfile()
        val doc = seedDocument(owner, "REFERENCE_LETTER")
        seedVersion(doc, 1, "sha-v1", "LOCKED")
        seedVersion(doc, 2, "sha-v2", "TOMBSTONED")
        val otherDoc = seedDocument(other, "EMPLOYMENT_PROOF")
        seedVersion(otherDoc, 1, "sha-other", "LOCKED")

        val data = documentExport.forOwner(owner)

        assertThat(data).hasSize(1)
        assertThat(data[0].documentId).isEqualTo(doc)
        assertThat(data[0].type).isEqualTo("REFERENCE_LETTER")
        assertThat(data[0].versions.map { it.versionNumber }).containsExactly(1, 2)
        assertThat(data[0].versions.map { it.sha256 }).containsExactly("sha-v1", "sha-v2")
        assertThat(data[0].versions.map { it.status }).containsExactly("LOCKED", "TOMBSTONED")
        assertThat(data[0].versions[0].lockedAt).isNotNull()
    }
}
