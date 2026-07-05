package com.verifolio.identity.application

import com.verifolio.identity.AccountExport
import com.verifolio.identity.AccountExportData
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class AccountExportImpl(private val dsl: DSLContext) : AccountExport {

    @Transactional(readOnly = true)
    override fun forUser(userId: UUID): AccountExportData? {
        val ua = USER_ACCOUNT
        return dsl.select(ua.EMAIL, ua.REGION, ua.STATUS, ua.CREATED_AT)
            .from(ua)
            .where(ua.ID.eq(userId))
            .fetchOne()
            ?.let {
                AccountExportData(
                    email = it[ua.EMAIL]!!,
                    region = it[ua.REGION]!!,
                    status = it[ua.STATUS]!!,
                    createdAt = it[ua.CREATED_AT]!!,
                )
            }
    }
}
