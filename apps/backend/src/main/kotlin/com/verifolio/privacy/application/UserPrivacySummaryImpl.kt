package com.verifolio.privacy.application

import com.verifolio.jooq.tables.records.ConsentRecordRecord
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.privacy.ConsentSummary
import com.verifolio.privacy.UserPrivacyData
import com.verifolio.privacy.UserPrivacySummary
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Privacy-owned implementation of the user privacy summary. Owner-scoped reads over consent_record
 * (ordered created_at ASC, matching the export's historical ordering) and data_subject_request
 * (counts grouped by status).
 */
@Service
internal class UserPrivacySummaryImpl(private val dsl: DSLContext) : UserPrivacySummary {

    @Transactional(readOnly = true)
    override fun forUser(userId: UUID): UserPrivacyData {
        val c = CONSENT_RECORD
        val consents = dsl.selectFrom(c)
            .where(c.USER_ID.eq(userId))
            .orderBy(c.CREATED_AT.asc())
            .fetch()
            .map { it.toConsentSummary() }

        val d = DATA_SUBJECT_REQUEST
        val counts = dsl.select(d.STATUS, DSL.count())
            .from(d)
            .where(d.USER_ID.eq(userId))
            .groupBy(d.STATUS)
            .fetch()
            .associate { it.value1()!! to it.value2() }

        return UserPrivacyData(consents, counts)
    }

    private fun ConsentRecordRecord.toConsentSummary() = ConsentSummary(
        consentType = consentType!!,
        status = status!!,
        policyTextVersion = policyTextVersion!!,
        grantedAt = grantedAt,
        declinedAt = declinedAt,
        withdrawnAt = withdrawnAt,
        createdAt = createdAt!!,
    )
}
