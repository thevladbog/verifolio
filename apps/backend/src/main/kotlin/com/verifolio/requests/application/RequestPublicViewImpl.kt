package com.verifolio.requests.application

import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.jooq.tables.references.REFERENCE_RESPONSE
import com.verifolio.requests.RequestPublicInfo
import com.verifolio.requests.RequestPublicView
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class RequestPublicViewImpl(private val dsl: DSLContext) : RequestPublicView {

    @Transactional(readOnly = true)
    override fun forRequest(requestId: UUID): RequestPublicInfo? {
        val rr = REFERENCE_REQUEST
        val record = dsl.selectFrom(rr).where(rr.ID.eq(requestId)).fetchOne() ?: return null

        val resp = REFERENCE_RESPONSE
        val submittedAt = dsl.select(resp.SUBMITTED_AT).from(resp)
            .where(resp.REQUEST_ID.eq(requestId).and(resp.SUBMITTED_AT.isNotNull))
            .orderBy(resp.SUBMITTED_AT.desc())
            .limit(1)
            .fetchOne(resp.SUBMITTED_AT)

        return RequestPublicInfo(
            recommenderName = record.recommenderName!!,
            relationshipType = record.recommenderRelationshipType,
            purpose = record.purpose,
            requestCreatedAt = record.createdAt!!,
            responseSubmittedAt = submittedAt,
        )
    }

    @Transactional(readOnly = true)
    override fun latestResponseId(requestId: UUID): UUID? {
        val resp = REFERENCE_RESPONSE
        return dsl.select(resp.ID).from(resp)
            .where(resp.REQUEST_ID.eq(requestId).and(resp.SUBMITTED_AT.isNotNull))
            .orderBy(resp.SUBMITTED_AT.desc())
            .limit(1)
            .fetchOne(resp.ID)
    }
}
