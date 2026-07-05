package com.verifolio.profiles

import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class ProfileExportIntegrationTest : IntegrationTest() {

    @Autowired lateinit var profileExport: ProfileExport
    @Autowired lateinit var dsl: DSLContext

    private fun seedUser(email: String): UUID {
        val id = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, id).set(ua.EMAIL, email).set(ua.REGION, "EU").set(ua.STATUS, "ACTIVE")
            .execute()
        return id
    }

    private fun seedProfile(userId: UUID, displayName: String, legalName: String?, locale: String): UUID {
        val id = UUID.randomUUID()
        val pp = PERSON_PROFILE
        dsl.insertInto(pp)
            .set(pp.ID, id)
            .set(pp.USER_ACCOUNT_ID, userId)
            .set(pp.DISPLAY_NAME, displayName)
            .set(pp.LEGAL_NAME, legalName)
            .set(pp.PREFERRED_LOCALE, locale)
            .execute()
        return id
    }

    @Test
    fun `returns the subject profile metadata and resolves by user`() {
        val userId = seedUser("profile-export-${UUID.randomUUID()}@example.com")
        seedProfile(userId, "Ada Lovelace", "Augusta Ada King", "en")

        val data = profileExport.forUser(userId)

        assertThat(data).isNotNull
        assertThat(data!!.displayName).isEqualTo("Ada Lovelace")
        assertThat(data.legalName).isEqualTo("Augusta Ada King")
        assertThat(data.preferredLocale).isEqualTo("en")
    }

    @Test
    fun `returns null when the user has no profile`() {
        val userId = seedUser("no-profile-${UUID.randomUUID()}@example.com")
        assertThat(profileExport.forUser(userId)).isNull()
    }
}
