package com.verifolio.admin.application

import com.verifolio.jooq.tables.references.ADMIN_MFA_PENDING
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Atomically claims an MFA attempt in an independent transaction so the count survives the
 * CODE_INVALID rollback of the caller (privacy DsrCodeAttemptRecorder precedent). The row-level
 * `WHERE attempts < max` guard means concurrent verify calls can never exceed the cap — closing
 * the read-check-then-act race.
 */
@Component
internal class AdminMfaAttemptRecorder(private val dsl: DSLContext) {

    /** Bumps `attempts` for [pendingId] only while below [maxAttempts]; returns rows updated (1=claimed, 0=capped). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimAttempt(pendingId: UUID, maxAttempts: Int): Int =
        dsl.update(ADMIN_MFA_PENDING)
            .set(ADMIN_MFA_PENDING.ATTEMPTS, ADMIN_MFA_PENDING.ATTEMPTS.plus(1))
            .where(ADMIN_MFA_PENDING.ID.eq(pendingId).and(ADMIN_MFA_PENDING.ATTEMPTS.lt(maxAttempts)))
            .execute()

    /**
     * Invalidates [pendingId] in an independent transaction so it survives the CODE_INVALID
     * rollback of the caller — used on attempt-cap exhaustion to truly retire the pending
     * (the caller's outer transaction is about to roll back).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun invalidate(pendingId: UUID) {
        dsl.update(ADMIN_MFA_PENDING)
            .set(ADMIN_MFA_PENDING.CONSUMED_AT, OffsetDateTime.now())
            .where(ADMIN_MFA_PENDING.ID.eq(pendingId).and(ADMIN_MFA_PENDING.CONSUMED_AT.isNull))
            .execute()
    }
}
