package com.verifolio.profiles

import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class ProfileErasureIntegrationTest : IntegrationTest() {

    @Autowired lateinit var profileErasure: ProfileErasure
    @Autowired lateinit var dsl: DSLContext

    private fun seedUser(): UUID {
        val id = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, id).set(ua.EMAIL, "profile-erase-${UUID.randomUUID()}@example.com")
            .set(ua.REGION, "EU").set(ua.STATUS, "ACTIVE").execute()
        return id
    }

    private fun seedProfile(userId: UUID) {
        val pp = PERSON_PROFILE
        dsl.insertInto(pp)
            .set(pp.ID, UUID.randomUUID())
            .set(pp.USER_ACCOUNT_ID, userId)
            .set(pp.DISPLAY_NAME, "Ada Lovelace")
            .set(pp.LEGAL_NAME, "Augusta Ada King")
            .set(pp.PREFERRED_LOCALE, "fr")
            .execute()
    }

    @Test
    fun `anonymizes the profile PII while retaining the row`() {
        val userId = seedUser()
        seedProfile(userId)

        profileErasure.eraseForUser(userId)

        val pp = PERSON_PROFILE
        val row = dsl.selectFrom(pp).where(pp.USER_ACCOUNT_ID.eq(userId)).fetchOne()
        assertThat(row).isNotNull()
        assertThat(row!![pp.DISPLAY_NAME]).isEqualTo("Deleted user")
        assertThat(row[pp.LEGAL_NAME]).isNull()
        // preferred_locale is a non-PII UI preference — left untouched.
        assertThat(row[pp.PREFERRED_LOCALE]).isEqualTo("fr")
    }

    @Test
    fun `is idempotent and a no-op when the user has no profile`() {
        val userId = seedUser()
        // No profile row seeded — must not throw.
        profileErasure.eraseForUser(userId)

        seedProfile(userId)
        profileErasure.eraseForUser(userId)
        profileErasure.eraseForUser(userId)

        val pp = PERSON_PROFILE
        val row = dsl.selectFrom(pp).where(pp.USER_ACCOUNT_ID.eq(userId)).fetchOne()
        assertThat(row!![pp.DISPLAY_NAME]).isEqualTo("Deleted user")
        assertThat(row[pp.LEGAL_NAME]).isNull()
    }
}
