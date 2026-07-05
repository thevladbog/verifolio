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

        val userId = findOrCreateUserAccount(email) ?: return

        // Duplicate-key-tolerant: a concurrent startup on another node may insert the same admin
        // between our existence check and here. ON CONFLICT DO NOTHING lets the loser fall through
        // (rows == 0) instead of aborting the whole bootstrap on the unique constraint.
        val inserted = dsl.insertInto(ADMIN_ACCOUNT)
            .set(ADMIN_ACCOUNT.USER_ACCOUNT_ID, userId)
            .set(ADMIN_ACCOUNT.EMAIL, email)
            .set(ADMIN_ACCOUNT.REGION, props.region)
            .set(ADMIN_ACCOUNT.ROLE, AdminRole.SUPERADMIN.name)
            .set(ADMIN_ACCOUNT.STATUS, "ACTIVE")
            .onConflictDoNothing()
            .execute()
        if (inserted == 0) return // another node beat us — do not double-audit.

        val adminId = dsl.select(ADMIN_ACCOUNT.ID).from(ADMIN_ACCOUNT)
            .where(DSL.lower(ADMIN_ACCOUNT.EMAIL).eq(email)).fetchOne()!!.value1()!!

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

    /**
     * Resolves the user_account to link, or null to skip bootstrapping this email. Lookup is
     * case-insensitive; an existing user is reused ONLY if it is in this cell's region and ACTIVE —
     * we never elevate a disabled or foreign-region user to SUPERADMIN. If no user exists yet, one is
     * created ACTIVE in-cell (duplicate-key-tolerant).
     */
    private fun findOrCreateUserAccount(email: String): java.util.UUID? {
        dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.REGION, USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT).where(DSL.lower(USER_ACCOUNT.EMAIL).eq(email)).fetchOne()
            ?.let { row ->
                val region = row.get(USER_ACCOUNT.REGION)
                val status = row.get(USER_ACCOUNT.STATUS)
                if (region != props.region || status != "ACTIVE") {
                    log.error(
                        "Skipping admin bootstrap: existing user_account for the email is region={} " +
                            "status={} (expected region={} status=ACTIVE)",
                        region, status, props.region,
                    )
                    return null
                }
                return row.get(USER_ACCOUNT.ID)!!
            }

        dsl.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.EMAIL, email)
            .set(USER_ACCOUNT.REGION, props.region)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .onConflict(USER_ACCOUNT.EMAIL).doNothing()
            .execute()
        return dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT)
            .where(DSL.lower(USER_ACCOUNT.EMAIL).eq(email)).fetchOne()!!.value1()!!
    }
}
