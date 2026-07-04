package com.verifolio.audit

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class AuditServiceIntegrationTest : IntegrationTest() {

    @Autowired lateinit var auditService: AuditService
    @Autowired lateinit var dsl: DSLContext

    @Test
    fun `records an append-only audit event`() {
        auditService.record(
            actorType = "USER",
            actorId = "user-1",
            action = "LOGIN_SUCCEEDED",
            entityType = "SESSION",
            entityId = "session-1",
            metadata = mapOf("region" to "local"),
            ipHash = "aa11",
            userAgentHash = "bb22",
        )
        val row = dsl.selectFrom(AUDIT_EVENT)
            .where(AUDIT_EVENT.ACTION.eq("LOGIN_SUCCEEDED"))
            .fetchOne()
        assertThat(row).isNotNull
        assertThat(row!!.actorType).isEqualTo("USER")
        assertThat(row.entityType).isEqualTo("SESSION")
    }
}
