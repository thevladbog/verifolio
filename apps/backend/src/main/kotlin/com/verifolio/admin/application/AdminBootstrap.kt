package com.verifolio.admin.application

import com.verifolio.admin.domain.AdminRole
import com.verifolio.audit.AuditService
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.platform.VerifolioProperties
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Config-driven, idempotent SUPERADMIN bootstrap (spec §Bootstrap). On ApplicationReadyEvent, for
 * each `verifolio.admin.bootstrap-emails` entry with no existing admin_account in this cell:
 * find-or-create a user_account (region = cell region, ACTIVE) and insert a SUPERADMIN
 * admin_account (mfa_enrolled_at null → must enroll on first login). Audited ADMIN_ACCOUNT_CREATED
 * (actor SYSTEM, IDs only). Empty list = no bootstrap, so integration tests are unaffected unless
 * they set the property.
 */
@Component
internal class AdminBootstrap(
    private val dsl: DSLContext,
    private val audit: AuditService,
    private val props: VerifolioProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun bootstrap() {
        if (props.admin.bootstrapEmails.isEmpty()) return
        props.admin.bootstrapEmails
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach(::createIfAbsent)
    }

    private fun createIfAbsent(email: String) {
        val exists = dsl.fetchExists(
            dsl.selectOne().from(ADMIN_ACCOUNT).where(DSL.lower(ADMIN_ACCOUNT.EMAIL).eq(email)),
        )
        if (exists) return

        val userId = findOrCreateUserAccount(email)
        val adminId = dsl.insertInto(ADMIN_ACCOUNT)
            .set(ADMIN_ACCOUNT.USER_ACCOUNT_ID, userId)
            .set(ADMIN_ACCOUNT.EMAIL, email)
            .set(ADMIN_ACCOUNT.REGION, props.region)
            .set(ADMIN_ACCOUNT.ROLE, AdminRole.SUPERADMIN.name)
            .set(ADMIN_ACCOUNT.STATUS, "ACTIVE")
            .returning(ADMIN_ACCOUNT.ID)
            .fetchOne()!!.id!!

        audit.record(
            actorType = "SYSTEM",
            actorId = null,
            action = "ADMIN_ACCOUNT_CREATED",
            entityType = "ADMIN_ACCOUNT",
            entityId = adminId.toString(),
            metadata = mapOf("adminId" to adminId.toString(), "role" to AdminRole.SUPERADMIN.name),
        )
        log.info("Bootstrapped SUPERADMIN admin_account {}", adminId)
    }

    private fun findOrCreateUserAccount(email: String): java.util.UUID {
        dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT).where(USER_ACCOUNT.EMAIL.eq(email)).fetchOne()
            ?.let { return it.value1()!! }

        dsl.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.EMAIL, email)
            .set(USER_ACCOUNT.REGION, props.region)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .onConflict(USER_ACCOUNT.EMAIL).doNothing()
            .execute()
        return dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT)
            .where(USER_ACCOUNT.EMAIL.eq(email)).fetchOne()!!.value1()!!
    }
}
