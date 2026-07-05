package com.verifolio.admin.application

import com.verifolio.admin.domain.AdminAccount
import com.verifolio.admin.domain.AdminRole
import com.verifolio.jooq.tables.records.AdminAccountRecord
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Read helper mapping the jOOQ admin_account record to the domain [AdminAccount]. */
@Component
internal class AdminAccounts(private val dsl: DSLContext) {

    @Transactional(readOnly = true)
    fun byId(id: UUID): AdminAccount? =
        dsl.selectFrom(ADMIN_ACCOUNT).where(ADMIN_ACCOUNT.ID.eq(id)).fetchOne()?.toDomain()

    /** The ACTIVE admin account for [email] (case-insensitive), or null. */
    @Transactional(readOnly = true)
    fun activeByEmail(email: String): AdminAccount? =
        dsl.selectFrom(ADMIN_ACCOUNT)
            .where(DSL.lower(ADMIN_ACCOUNT.EMAIL).eq(email.trim().lowercase()))
            .and(ADMIN_ACCOUNT.STATUS.eq("ACTIVE"))
            .fetchOne()?.toDomain()

    private fun AdminAccountRecord.toDomain() = AdminAccount(
        id = id!!,
        userAccountId = userAccountId!!,
        email = email!!,
        region = region!!,
        role = AdminRole.valueOf(role!!),
        status = status!!,
        totpSecretEnc = totpSecretEnc,
        mfaEnrolledAt = mfaEnrolledAt,
    )
}
