package com.verifolio.audit.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.verifolio.audit.AuditService
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Service

@Service
internal class JooqAuditRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : AuditService {

    override fun record(
        actorType: String, actorId: String?, action: String,
        entityType: String?, entityId: String?,
        metadata: Map<String, String>, ipHash: String?, userAgentHash: String?,
    ) {
        dsl.insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ACTOR_TYPE, actorType)
            .set(AUDIT_EVENT.ACTOR_ID, actorId)
            .set(AUDIT_EVENT.ACTION, action)
            .set(AUDIT_EVENT.ENTITY_TYPE, entityType)
            .set(AUDIT_EVENT.ENTITY_ID, entityId)
            .set(AUDIT_EVENT.METADATA, JSONB.jsonb(objectMapper.writeValueAsString(metadata)))
            .set(AUDIT_EVENT.IP_HASH, ipHash)
            .set(AUDIT_EVENT.USER_AGENT_HASH, userAgentHash)
            .execute()
    }
}
