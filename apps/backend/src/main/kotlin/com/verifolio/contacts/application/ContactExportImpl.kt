package com.verifolio.contacts.application

import com.verifolio.contacts.ContactExport
import com.verifolio.contacts.ContactExportData
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class ContactExportImpl(private val dsl: DSLContext) : ContactExport {

    @Transactional(readOnly = true)
    override fun forOwner(ownerProfileId: UUID): List<ContactExportData> {
        val rc = RECOMMENDER_CONTACT
        return dsl.select(rc.NAME, rc.EMAIL, rc.COMPANY_NAME, rc.RELATIONSHIP_TYPE, rc.CREATED_AT)
            .from(rc)
            .where(rc.OWNER_PROFILE_ID.eq(ownerProfileId))
            .orderBy(rc.CREATED_AT.asc(), rc.ID.asc())
            .fetch()
            .map {
                ContactExportData(
                    name = it[rc.NAME]!!,
                    email = it[rc.EMAIL]!!,
                    companyName = it[rc.COMPANY_NAME],
                    relationshipType = it[rc.RELATIONSHIP_TYPE]!!,
                    createdAt = it[rc.CREATED_AT]!!,
                )
            }
    }
}
