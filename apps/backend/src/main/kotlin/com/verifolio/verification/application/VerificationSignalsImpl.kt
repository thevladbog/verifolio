package com.verifolio.verification.application

import com.verifolio.audit.AuditService
import com.verifolio.jooq.tables.references.VERIFICATION_SIGNAL
import com.verifolio.verification.VerificationSignals
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

@Service
internal class VerificationSignalsImpl(
    private val dsl: DSLContext,
    private val audit: AuditService,
    private val objectMapper: ObjectMapper,
) : VerificationSignals {

    @Transactional
    override fun createVerified(
        entityType: String,
        entityId: UUID,
        signalType: String,
        evidence: Map<String, String>,
        provider: String?,
    ): UUID {
        val vs = VERIFICATION_SIGNAL
        val id = dsl.insertInto(vs)
            .set(vs.ENTITY_TYPE, entityType)
            .set(vs.ENTITY_ID, entityId)
            .set(vs.SIGNAL_TYPE, signalType)
            .set(vs.STATUS, "VERIFIED")
            .set(vs.EVIDENCE_JSON, JSONB.valueOf(objectMapper.writeValueAsString(evidence)))
            .set(vs.PROVIDER, provider)
            .set(vs.VERIFIED_AT, OffsetDateTime.now())
            .returning(vs.ID)
            .fetchOne()!!.id!!

        audit.record(
            actorType = "SYSTEM",
            actorId = null,
            action = "VERIFICATION_SIGNAL_CREATED",
            entityType = "VERIFICATION_SIGNAL",
            entityId = id.toString(),
            metadata = mapOf(
                "signalType" to signalType,
                "entityType" to entityType,
                "entityId" to entityId.toString(),
            ),
        )
        return id
    }
}
