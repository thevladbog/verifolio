package com.verifolio.profiles.application

import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.profiles.ProfileExport
import com.verifolio.profiles.ProfileExportData
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class ProfileExportImpl(private val dsl: DSLContext) : ProfileExport {

    @Transactional(readOnly = true)
    override fun forUser(userId: UUID): ProfileExportData? {
        val pp = PERSON_PROFILE
        return dsl.select(pp.DISPLAY_NAME, pp.LEGAL_NAME, pp.PREFERRED_LOCALE)
            .from(pp)
            .where(pp.USER_ACCOUNT_ID.eq(userId))
            .fetchOne()
            ?.let {
                ProfileExportData(
                    displayName = it[pp.DISPLAY_NAME]!!,
                    legalName = it[pp.LEGAL_NAME],
                    preferredLocale = it[pp.PREFERRED_LOCALE]!!,
                )
            }
    }
}
