package com.verifolio.verification.application

import com.verifolio.audit.AuditService
import com.verifolio.jooq.tables.references.VERIFICATION_SIGNAL
import com.verifolio.verification.SignalView
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

    @Transactional(readOnly = true)
    override fun listVerified(entityType: String, entityId: UUID): List<SignalView> {
        val vs = VERIFICATION_SIGNAL
        return dsl.selectFrom(vs)
            .where(vs.ENTITY_TYPE.eq(entityType).and(vs.ENTITY_ID.eq(entityId)).and(vs.STATUS.eq("VERIFIED")))
            .orderBy(vs.CREATED_AT.asc())
            .fetch()
            .map { SignalView(it.signalType!!, it.status!!, it.verifiedAt, parseEvidence(it.evidenceJson)) }
    }

    @Transactional(readOnly = true)
    override fun listForDisplay(entityType: String, entityId: UUID): List<SignalView> {
        val vs = VERIFICATION_SIGNAL
        return dsl.selectFrom(vs)
            .where(
                vs.ENTITY_TYPE.eq(entityType)
                    .and(vs.ENTITY_ID.eq(entityId))
                    .and(vs.STATUS.`in`("VERIFIED", "REVOKED")),
            )
            .orderBy(vs.CREATED_AT.asc())
            .fetch()
            .map { SignalView(it.signalType!!, it.status!!, it.verifiedAt, parseEvidence(it.evidenceJson)) }
    }

    @Transactional
    override fun markRevoked(entityType: String, entityId: UUID, signalType: String): Int =
        flipVerified(entityType, entityId, signalType, "REVOKED")

    @Transactional
    override fun revokeAllForEntity(entityType: String, entityId: UUID): Int =
        flipVerified(entityType, entityId, signalType = null, newStatus = "REVOKED")

    @Transactional
    override fun markExpired(entityType: String, entityId: UUID, signalType: String): Int =
        flipVerified(entityType, entityId, signalType, "EXPIRED")

    /**
     * Reads evidence_json back into a string map for display surfaces. Values are always
     * stored as strings by [createVerified]; a null or unparseable payload yields empty.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseEvidence(json: JSONB?): Map<String, String> {
        val raw = json?.data() ?: return emptyMap()
        return runCatching {
            (objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>)
                .mapNotNull { (k, v) -> if (v == null) null else k to v.toString() }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    /** [signalType] null flips every VERIFIED signal on the entity regardless of type. */
    private fun flipVerified(entityType: String, entityId: UUID, signalType: String?, newStatus: String): Int {
        val vs = VERIFICATION_SIGNAL
        val typeCondition = if (signalType != null) vs.SIGNAL_TYPE.eq(signalType) else org.jooq.impl.DSL.noCondition()
        val flipped = dsl.update(vs)
            .set(vs.STATUS, newStatus)
            .where(
                vs.ENTITY_TYPE.eq(entityType)
                    .and(vs.ENTITY_ID.eq(entityId))
                    .and(typeCondition)
                    .and(vs.STATUS.eq("VERIFIED")),
            )
            .returning(vs.ID, vs.SIGNAL_TYPE)
            .fetch()
        flipped.forEach { row ->
            audit.record(
                actorType = "SYSTEM",
                actorId = null,
                action = "VERIFICATION_SIGNAL_UPDATED",
                entityType = "VERIFICATION_SIGNAL",
                entityId = row.id.toString(),
                metadata = mapOf(
                    "signalType" to row.signalType.toString(),
                    "entityType" to entityType,
                    "entityId" to entityId.toString(),
                    "newStatus" to newStatus,
                ),
            )
        }
        return flipped.size
    }
}
