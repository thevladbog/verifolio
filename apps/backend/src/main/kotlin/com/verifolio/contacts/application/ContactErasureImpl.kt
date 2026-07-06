package com.verifolio.contacts.application

import com.verifolio.contacts.ContactErasure
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** Placeholders for the NOT-NULL columns once the owner's contacts are anonymized. */
private const val DELETED_CONTACT_NAME = "Deleted contact"

@Service
internal class ContactErasureImpl(private val dsl: DSLContext) : ContactErasure {

    @Transactional
    override fun eraseForOwner(ownerProfileId: UUID): Int {
        val rc = RECOMMENDER_CONTACT
        // Rows RETAINED for FK RESTRICT from requests/consents. name + email are NOT NULL →
        // blanked to non-PII placeholders; company_name/company_domain/title are nullable →
        // nulled; relationship_type is a non-PII enum, kept. Idempotent: re-running sets the
        // same values.
        return dsl.update(rc)
            .set(rc.NAME, DELETED_CONTACT_NAME)
            .set(rc.EMAIL, "")
            .setNull(rc.COMPANY_NAME)
            .setNull(rc.COMPANY_DOMAIN)
            .setNull(rc.TITLE)
            .set(rc.UPDATED_AT, OffsetDateTime.now())
            .where(rc.OWNER_PROFILE_ID.eq(ownerProfileId))
            .execute()
    }
}
