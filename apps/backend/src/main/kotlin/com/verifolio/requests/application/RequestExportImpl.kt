package com.verifolio.requests.application

import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.requests.RequestExport
import com.verifolio.requests.RequestExportData
import org.jooq.Condition
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class RequestExportImpl(private val dsl: DSLContext) : RequestExport {

    @Transactional(readOnly = true)
    override fun forRequester(requesterProfileId: UUID): List<RequestExportData> {
        val rr = REFERENCE_REQUEST
        return query(rr.REQUESTER_PROFILE_ID.eq(requesterProfileId))
    }

    @Transactional(readOnly = true)
    override fun forRecommenderEmail(email: String): List<RequestExportData> {
        val rr = REFERENCE_REQUEST
        return query(rr.RECOMMENDER_EMAIL.eq(email))
    }

    private fun query(condition: Condition): List<RequestExportData> {
        val rr = REFERENCE_REQUEST
        return dsl.select(
            rr.ID, rr.RECOMMENDER_NAME, rr.RECOMMENDER_EMAIL, rr.PURPOSE,
            rr.STATUS, rr.CREATED_AT, rr.UPDATED_AT,
        )
            .from(rr)
            .where(condition)
            .orderBy(rr.CREATED_AT.asc(), rr.ID.asc())
            .fetch()
            .map {
                RequestExportData(
                    id = it[rr.ID]!!,
                    recommenderName = it[rr.RECOMMENDER_NAME],
                    recommenderEmail = it[rr.RECOMMENDER_EMAIL],
                    purpose = it[rr.PURPOSE],
                    status = it[rr.STATUS]!!,
                    createdAt = it[rr.CREATED_AT]!!,
                    updatedAt = it[rr.UPDATED_AT]!!,
                )
            }
    }
}
