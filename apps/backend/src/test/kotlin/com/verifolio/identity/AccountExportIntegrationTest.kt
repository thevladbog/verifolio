package com.verifolio.identity

import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class AccountExportIntegrationTest : IntegrationTest() {

    @Autowired lateinit var accountExport: AccountExport
    @Autowired lateinit var dsl: DSLContext

    private fun seedAccount(email: String): UUID {
        val id = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, id)
            .set(ua.EMAIL, email)
            .set(ua.REGION, "EU")
            .set(ua.STATUS, "ACTIVE")
            .execute()
        return id
    }

    @Test
    fun `returns the subject account metadata`() {
        val userId = seedAccount("account-export-${UUID.randomUUID()}@example.com")

        val data = accountExport.forUser(userId)

        assertThat(data).isNotNull()
        assertThat(data!!.region).isEqualTo("EU")
        assertThat(data.status).isEqualTo("ACTIVE")
        assertThat(data.createdAt).isNotNull()
    }

    @Test
    fun `returns null for an unknown user`() {
        assertThat(accountExport.forUser(UUID.randomUUID())).isNull()
    }
}
