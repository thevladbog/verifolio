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

class ContactExportIntegrationTest : IntegrationTest() {

    @Autowired lateinit var contactExport: ContactExport
    @Autowired lateinit var dsl: DSLContext

    private fun seedProfile(): UUID {
        val userId = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, userId).set(ua.EMAIL, "owner-${UUID.randomUUID()}@example.com")
            .set(ua.REGION, "EU").set(ua.STATUS, "ACTIVE").execute()
        val profileId = UUID.randomUUID()
        val pp = PERSON_PROFILE
        dsl.insertInto(pp)
            .set(pp.ID, profileId).set(pp.USER_ACCOUNT_ID, userId)
            .set(pp.DISPLAY_NAME, "Owner").set(pp.PREFERRED_LOCALE, "en").execute()
        return profileId
    }

    private fun seedContact(ownerProfileId: UUID, name: String, email: String, company: String?) {
        val rc = RECOMMENDER_CONTACT
        dsl.insertInto(rc)
            .set(rc.ID, UUID.randomUUID())
            .set(rc.OWNER_PROFILE_ID, ownerProfileId)
            .set(rc.NAME, name)
            .set(rc.EMAIL, email)
            .set(rc.COMPANY_NAME, company)
            .set(rc.RELATIONSHIP_TYPE, "MANAGER")
            .execute()
    }

    @Test
    fun `returns only the owner's contacts, excluding other owners`() {
        val owner = seedProfile()
        val other = seedProfile()
        seedContact(owner, "Grace Hopper", "grace@navy.mil", "US Navy")
        seedContact(owner, "Alan Turing", "alan@bletchley.uk", null)
        seedContact(other, "Someone Else", "else@other.com", "Other Co")

        val data = contactExport.forOwner(owner)

        assertThat(data).hasSize(2)
        assertThat(data.map { it.name }).containsExactlyInAnyOrder("Grace Hopper", "Alan Turing")
        assertThat(data.map { it.email }).doesNotContain("else@other.com")
        val grace = data.first { it.name == "Grace Hopper" }
        assertThat(grace.companyName).isEqualTo("US Navy")
        assertThat(grace.relationshipType).isEqualTo("MANAGER")
        assertThat(grace.createdAt).isNotNull()
    }
}
