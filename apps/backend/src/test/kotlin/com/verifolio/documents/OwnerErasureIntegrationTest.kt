package com.verifolio.documents

import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.JSONB
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

class OwnerErasureIntegrationTest : IntegrationTest() {

    @Autowired lateinit var ownerErasure: OwnerErasure
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

    private fun seedDocument(ownerProfileId: UUID): UUID {
        val id = UUID.randomUUID()
        val d = DOCUMENT
        dsl.insertInto(d)
            .set(d.ID, id).set(d.OWNER_PROFILE_ID, ownerProfileId).set(d.TYPE, "REFERENCE_LETTER")
            .execute()
        return id
    }

    private fun seedLockedVersion(documentId: UUID, sha: String): UUID {
        val id = UUID.randomUUID()
        val dv = DOCUMENT_VERSION
        dsl.insertInto(dv)
            .set(dv.ID, id)
            .set(dv.DOCUMENT_ID, documentId)
            .set(dv.VERSION_NUMBER, 1)
            .set(dv.CONTENT_JSON, JSONB.jsonb("""{"body":"secret letter"}"""))
            .set(dv.RENDERED_HTML, "<p>secret letter</p>")
            .set(dv.SHA256_HASH, sha)
            .set(dv.STATUS, "LOCKED")
            .set(dv.LOCKED_AT, OffsetDateTime.now())
            .execute()
        return id
    }

    @Test
    fun `tombstones the owner's versions, retains the hash, and leaves other owners untouched`() {
        val owner = seedProfile()
        val other = seedProfile()
        val ownerDoc = seedDocument(owner)
        val ownerVersion = seedLockedVersion(ownerDoc, "sha-owner")
        val otherDoc = seedDocument(other)
        val otherVersion = seedLockedVersion(otherDoc, "sha-other")

        val tombstoned = ownerErasure.tombstoneForOwner(owner)

        assertThat(tombstoned).containsExactly(ownerVersion)
        val dv = DOCUMENT_VERSION
        val erased = dsl.selectFrom(dv).where(dv.ID.eq(ownerVersion)).fetchOne()!!
        assertThat(erased.status).isEqualTo("TOMBSTONED")
        assertThat(erased.contentJson).isNull()
        assertThat(erased.renderedHtml).isNull()
        // Integrity anchors retained.
        assertThat(erased.sha256Hash).isEqualTo("sha-owner")
        assertThat(erased.lockedAt).isNotNull
        assertThat(erased.tombstonedAt).isNotNull

        // The other owner's version is untouched.
        val untouched = dsl.selectFrom(dv).where(dv.ID.eq(otherVersion)).fetchOne()!!
        assertThat(untouched.status).isEqualTo("LOCKED")
        assertThat(untouched.contentJson).isNotNull
        assertThat(untouched.sha256Hash).isEqualTo("sha-other")

        // Idempotent: a re-run finds nothing left to tombstone.
        assertThat(ownerErasure.tombstoneForOwner(owner)).isEmpty()
    }
}
