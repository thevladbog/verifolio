package com.verifolio.contacts.application

import com.verifolio.contacts.ContactLookup
import com.verifolio.contacts.ContactSnapshot
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class ContactLookupImpl(private val dsl: DSLContext) : ContactLookup {
    override fun findOwned(contactId: UUID, ownerProfileId: UUID): ContactSnapshot? {
        val rc = RECOMMENDER_CONTACT
        return dsl.select(rc.ID, rc.NAME, rc.EMAIL).from(rc)
            .where(rc.ID.eq(contactId).and(rc.OWNER_PROFILE_ID.eq(ownerProfileId)))
            .fetchOne()
            ?.let { ContactSnapshot(it[rc.ID]!!, it[rc.NAME]!!, it[rc.EMAIL]!!) }
    }
}
