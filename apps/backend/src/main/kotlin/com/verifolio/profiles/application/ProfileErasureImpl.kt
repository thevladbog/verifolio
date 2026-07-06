package com.verifolio.profiles.application

import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.profiles.ProfileErasure
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** Placeholder for the NOT-NULL `display_name` once the subject's PII is erased. */
private const val DELETED_DISPLAY_NAME = "Deleted user"

@Service
internal class ProfileErasureImpl(private val dsl: DSLContext) : ProfileErasure {

    @Transactional
    override fun eraseForUser(userId: UUID) {
        val pp = PERSON_PROFILE
        // Row RETAINED (FK integrity). display_name is NOT NULL → placeholder; legal_name
        // nulled; preferred_locale (non-PII UI preference) left as-is. Idempotent: re-running
        // sets the same values.
        dsl.update(pp)
            .set(pp.DISPLAY_NAME, DELETED_DISPLAY_NAME)
            .setNull(pp.LEGAL_NAME)
            .set(pp.UPDATED_AT, OffsetDateTime.now())
            .where(pp.USER_ACCOUNT_ID.eq(userId))
            .execute()
    }
}
