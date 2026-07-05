package com.verifolio.admin

import com.verifolio.admin.application.AdminBootstrap
import com.verifolio.admin.domain.AdminRole
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Bootstrap runs on ApplicationReadyEvent for this context (bootstrap-emails set below). Verifies a
 * SUPERADMIN is created idempotently.
 */
class AdminBootstrapIntegrationTest : IntegrationTest() {

    @Autowired lateinit var dsl: DSLContext
    @Autowired internal lateinit var bootstrap: AdminBootstrap

    companion object {
        const val BOOTSTRAP_EMAIL = "bootstrap-admin@example.com"

        @JvmStatic
        @DynamicPropertySource
        fun adminProps(registry: DynamicPropertyRegistry) {
            // Mixed case + a blank entry to exercise normalization/filtering.
            registry.add("verifolio.admin.bootstrap-emails") { "Bootstrap-Admin@Example.com, " }
        }
    }

    private fun adminCount() = dsl.selectCount().from(ADMIN_ACCOUNT)
        .where(DSL.lower(ADMIN_ACCOUNT.EMAIL).eq(BOOTSTRAP_EMAIL))
        .fetchOne(0, Int::class.java)!!

    @Test
    fun `bootstrap created a SUPERADMIN idempotently`() {
        // Created on startup.
        assertThat(adminCount()).isEqualTo(1)
        val row = dsl.selectFrom(ADMIN_ACCOUNT)
            .where(DSL.lower(ADMIN_ACCOUNT.EMAIL).eq(BOOTSTRAP_EMAIL)).fetchOne()!!
        assertThat(row.role).isEqualTo(AdminRole.SUPERADMIN.name)
        assertThat(row.status).isEqualTo("ACTIVE")
        assertThat(row.region).isEqualTo("local")
        assertThat(row.mfaEnrolledAt).isNull() // must enroll on first login
        assertThat(row.totpSecretEnc).isNull()
        // Linked user_account exists in-cell.
        val userRegion = dsl.select(USER_ACCOUNT.REGION).from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(row.userAccountId)).fetchOne()!!.value1()
        assertThat(userRegion).isEqualTo("local")

        // Re-running is a no-op (idempotent).
        bootstrap.bootstrap()
        assertThat(adminCount()).isEqualTo(1)
    }
}
