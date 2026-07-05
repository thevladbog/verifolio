package com.verifolio.requests.application

import com.verifolio.audit.AuditService
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.requests.ConsentWithdrawal
import com.verifolio.requests.RecommenderRequestRef
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Owns the consent_record write for withdrawal and the email→request resolution used by the
 * recommender DSR channel. Never deletes consent rows (they evidence lawful basis); withdrawal
 * only flips status to WITHDRAWN.
 */
@Service
internal class ConsentWithdrawalImpl(
    private val dsl: DSLContext,
    private val audit: AuditService,
) : ConsentWithdrawal {

    @Transactional
    override fun withdrawForRequest(requestId: UUID, recommenderContactId: UUID): Int {
        val cr = CONSENT_RECORD
        val flipped = dsl.update(cr)
            .set(cr.STATUS, "WITHDRAWN")
            .set(cr.WITHDRAWN_AT, OffsetDateTime.now())
            .where(
                cr.REFERENCE_REQUEST_ID.eq(requestId)
                    .and(cr.RECOMMENDER_CONTACT_ID.eq(recommenderContactId))
                    .and(cr.STATUS.eq("GRANTED")),
            )
            .execute()

        if (flipped > 0) {
            audit.record(
                actorType = "SYSTEM",
                actorId = null,
                action = "CONSENT_WITHDRAWN",
                entityType = "REFERENCE_REQUEST",
                entityId = requestId.toString(),
                metadata = mapOf(
                    "requestId" to requestId.toString(),
                    "recommenderContactId" to recommenderContactId.toString(),
                    "count" to flipped.toString(),
                ),
            )
        }
        return flipped
    }

    @Transactional(readOnly = true)
    override fun findRequestsByRecommenderEmail(email: String): List<RecommenderRequestRef> {
        val normalized = email.trim().lowercase()
        val rr = REFERENCE_REQUEST
        val rc = RECOMMENDER_CONTACT
        return dsl.select(rr.ID, rr.RECOMMENDER_CONTACT_ID)
            .from(rr)
            .join(rc).on(rc.ID.eq(rr.RECOMMENDER_CONTACT_ID))
            .where(
                org.jooq.impl.DSL.lower(rr.RECOMMENDER_EMAIL).eq(normalized)
                    .or(org.jooq.impl.DSL.lower(rc.EMAIL).eq(normalized)),
            )
            .orderBy(rr.CREATED_AT.desc())
            .fetch { RecommenderRequestRef(it.value1()!!, it.value2()!!) }
    }
}
