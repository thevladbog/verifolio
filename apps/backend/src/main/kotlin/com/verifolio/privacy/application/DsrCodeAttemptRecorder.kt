package com.verifolio.privacy.application

import com.verifolio.jooq.tables.references.DSR_VERIFICATION_CODE
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Atomically claims a verification attempt in an independent transaction so the count survives
 * the CODE_INVALID rollback of the caller (identity ConfirmationAttemptRecorder precedent).
 */
@Component
internal class DsrCodeAttemptRecorder(private val dsl: DSLContext) {

    /**
     * Atomic conditional increment: bumps `attempts` for [codeId] only while it is still below
     * [maxAttempts], returning the number of rows updated (1 = a slot was claimed, 0 = the cap is
     * already reached). Running in REQUIRES_NEW commits the increment even when the caller then
     * throws CODE_INVALID, and the row-level `WHERE attempts < max` guard means concurrent verify
     * calls cannot claim more than [maxAttempts] slots (no read-check-then-act race).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimAttempt(codeId: UUID, maxAttempts: Int): Int {
        val dvc = DSR_VERIFICATION_CODE
        return dsl.update(dvc)
            .set(dvc.ATTEMPTS, dvc.ATTEMPTS.plus(1))
            .where(dvc.ID.eq(codeId).and(dvc.ATTEMPTS.lt(maxAttempts)))
            .execute()
    }
}
