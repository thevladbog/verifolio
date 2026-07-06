package com.verifolio.audit

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class AuditPseudonymizerIntegrationTest : IntegrationTest() {

    @Autowired lateinit var auditPseudonymizer: AuditPseudonymizer
    @Autowired lateinit var dsl: DSLContext

    private fun seedEvent(actorId: String?): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ID, id)
            .set(AUDIT_EVENT.ACTOR_TYPE, "USER")
            .set(AUDIT_EVENT.ACTOR_ID, actorId)
            .set(AUDIT_EVENT.ACTION, "SOMETHING_HAPPENED")
            .execute()
        return id
    }

    @Test
    fun `nulls the actor on matching rows, retains all rows, and leaves others untouched`() {
        val subject = UUID.randomUUID().toString()
        val otherActor = UUID.randomUUID().toString()
        val subjectEvent1 = seedEvent(subject)
        val subjectEvent2 = seedEvent(subject)
        val otherEvent = seedEvent(otherActor)

        val count = auditPseudonymizer.pseudonymizeActor(subject)

        assertThat(count).isEqualTo(2)
        val ae = AUDIT_EVENT
        // Rows still present, only actor_id nulled.
        listOf(subjectEvent1, subjectEvent2).forEach { id ->
            val row = dsl.selectFrom(ae).where(ae.ID.eq(id)).fetchOne()!!
            assertThat(row.actorId).isNull()
            assertThat(row.action).isEqualTo("SOMETHING_HAPPENED")
        }
        val other = dsl.selectFrom(ae).where(ae.ID.eq(otherEvent)).fetchOne()!!
        assertThat(other.actorId).isEqualTo(otherActor)

        // Idempotent: nothing left to match.
        assertThat(auditPseudonymizer.pseudonymizeActor(subject)).isZero()
    }
}
