package com.verifolio.documents.application

import com.verifolio.jooq.tables.references.SHARE_LINK
import com.verifolio.platform.VerifolioProperties
import com.verifolio.verification.VerificationSignals
import com.verifolio.workflows.RecurringTask
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Expired, unrevoked share links stop resolving immediately (access check), but their
 * PUBLIC_VERIFICATION_ENABLED signal row needs the sweep to reflect EXPIRED
 * (docs/VERIFICATION_SIGNALS.md: the signal mirrors the share-link state).
 */
@Component
internal class ExpiredShareLinkSignalTask(
    private val dsl: DSLContext,
    private val verificationSignals: VerificationSignals,
    private val props: VerifolioProperties,
) : RecurringTask {

    override val name = "expired-share-link-signals"
    override val interval: Duration get() = props.workflows.cleanupInterval

    @Transactional
    override fun run() {
        val sl = SHARE_LINK
        val expiredLinkIds = dsl.select(sl.ID).from(sl)
            .where(sl.EXPIRES_AT.le(OffsetDateTime.now()).and(sl.REVOKED_AT.isNull))
            .fetch(sl.ID)
        expiredLinkIds.filterNotNull().forEach { linkId ->
            // markExpired flips VERIFIED rows only — already-flipped links are no-ops.
            verificationSignals.markExpired("SHARE_LINK", linkId, "PUBLIC_VERIFICATION_ENABLED")
        }
    }
}
