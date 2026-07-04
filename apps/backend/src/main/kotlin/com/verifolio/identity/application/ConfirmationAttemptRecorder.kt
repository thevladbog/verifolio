package com.verifolio.identity.application

import com.verifolio.jooq.tables.references.EMAIL_CONFIRMATION_CODE
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Persists failed-attempt counts in an independent transaction so they survive the
 * CODE_INVALID rollback of the caller (same pattern as AuditService).
 */
@Component
internal class ConfirmationAttemptRecorder(private val dsl: DSLContext) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(codeId: UUID) {
        val ecc = EMAIL_CONFIRMATION_CODE
        dsl.update(ecc)
            .set(ecc.ATTEMPTS, ecc.ATTEMPTS.plus(1))
            .where(ecc.ID.eq(codeId))
            .execute()
    }
}
