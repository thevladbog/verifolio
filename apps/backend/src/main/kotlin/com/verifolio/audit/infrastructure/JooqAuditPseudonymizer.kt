package com.verifolio.audit.infrastructure

import com.verifolio.audit.AuditPseudonymizer
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
internal class JooqAuditPseudonymizer(private val dsl: DSLContext) : AuditPseudonymizer {

    @Transactional
    override fun pseudonymizeActor(actorId: String): Int {
        // Rows are NEVER deleted — only the actor reference is nulled, so the processing trail
        // survives as an anonymous record. Idempotent: a re-run matches nothing (actor_id is
        // already null) and returns 0.
        return dsl.update(AUDIT_EVENT)
            .setNull(AUDIT_EVENT.ACTOR_ID)
            .where(AUDIT_EVENT.ACTOR_ID.eq(actorId))
            .execute()
    }
}
