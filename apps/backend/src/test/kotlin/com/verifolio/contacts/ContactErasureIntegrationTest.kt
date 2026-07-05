package com.verifolio.contacts

import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class ContactErasureIntegrationTest : IntegrationTest() {

    @Autowired lateinit var contactErasure: ContactErasure
    @Autowired lateinit var dsl: DSLContext

    private fun seedOwner(): UUID {
        val userId = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, userId).set(ua.EMAIL, "contact-owner-${UUID.randomUUID()}@example.com")
            .set(ua.REGION, "EU").set(ua.STATUS, "ACTIVE").execute()
        val profileId = UUID.randomUUID()
        val pp = PERSON_PROFILE
        dsl.insertInto(pp)
            .set(pp.ID, profileId).set(pp.USER_ACCOUNT_ID, userId)
            .set(pp.DISPLAY_NAME, "Owner").set(pp.PREFERRED_LOCALE, "en").execute()
        return profileId
    }

    private fun seedContact(ownerProfileId: UUID, name: String): UUID {
        val id = UUID.randomUUID()
        val rc = RECOMMENDER_CONTACT
        dsl.insertInto(rc)
            .set(rc.ID, id)
            .set(rc.OWNER_PROFILE_ID, ownerProfileId)
            .set(rc.NAME, name)
            .set(rc.EMAIL, "$name@example.com")
            .set(rc.COMPANY_NAME, "Acme")
            .set(rc.COMPANY_DOMAIN, "acme.com")
            .set(rc.TITLE, "Manager")
            .set(rc.RELATIONSHIP_TYPE, "MANAGER")
            .execute()
        return id
    }

    @Test
    fun `anonymizes the owner's contacts and leaves other owners untouched`() {
        val owner = seedOwner()
        val other = seedOwner()
        val c1 = seedContact(owner, "Grace")
        val c2 = seedContact(owner, "Alan")
        val otherContact = seedContact(other, "Katherine")

        val count = contactErasure.eraseForOwner(owner)

        assertThat(count).isEqualTo(2)
        val rc = RECOMMENDER_CONTACT
        listOf(c1, c2).forEach { id ->
            val row = dsl.selectFrom(rc).where(rc.ID.eq(id)).fetchOne()!!
            assertThat(row[rc.NAME]).isEqualTo("Deleted contact")
            assertThat(row[rc.EMAIL]).isEqualTo("")
            assertThat(row[rc.COMPANY_NAME]).isNull()
            assertThat(row[rc.COMPANY_DOMAIN]).isNull()
            assertThat(row[rc.TITLE]).isNull()
            // relationship_type + the row itself are retained (FK RESTRICT holds).
            assertThat(row[rc.RELATIONSHIP_TYPE]).isEqualTo("MANAGER")
        }
        val untouched = dsl.selectFrom(rc).where(rc.ID.eq(otherContact)).fetchOne()!!
        assertThat(untouched[rc.NAME]).isEqualTo("Katherine")
        assertThat(untouched[rc.EMAIL]).isEqualTo("Katherine@example.com")
        assertThat(untouched[rc.COMPANY_NAME]).isEqualTo("Acme")
    }

    @Test
    fun `is idempotent`() {
        val owner = seedOwner()
        seedContact(owner, "Grace")

        assertThat(contactErasure.eraseForOwner(owner)).isEqualTo(1)
        // A re-run still matches the (retained) row but changes nothing observable.
        assertThat(contactErasure.eraseForOwner(owner)).isEqualTo(1)

        val rc = RECOMMENDER_CONTACT
        val row = dsl.selectFrom(rc).where(rc.OWNER_PROFILE_ID.eq(owner)).fetchOne()!!
        assertThat(row[rc.NAME]).isEqualTo("Deleted contact")
        assertThat(row[rc.EMAIL]).isEqualTo("")
    }
}
