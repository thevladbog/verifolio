package com.verifolio.privacy.application

import com.verifolio.jooq.tables.references.DSR_VERIFICATION_CODE
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Persists failed DSR-code attempts in an independent transaction so the count survives the
 * CODE_INVALID rollback of the caller (identity ConfirmationAttemptRecorder precedent).
 */
@Component
internal class DsrCodeAttemptRecorder(private val dsl: DSLContext) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(codeId: UUID) {
        val dvc = DSR_VERIFICATION_CODE
        dsl.update(dvc)
            .set(dvc.ATTEMPTS, dvc.ATTEMPTS.plus(1))
            .where(dvc.ID.eq(codeId))
            .execute()
    }
}
