package com.verifolio.verification

import java.util.UUID

/**
 * Public write API of the verification module — the single owner of signal records
 * (docs/MODULES.md). Other modules consume read models only (read API ships with the
 * public verification page iteration).
 */
interface VerificationSignals {
    /**
     * Creates a VERIFIED signal (verified_at = now). Signal semantics must follow the
     * catalog in docs/VERIFICATION_SIGNALS.md; evidence must not contain emails, names,
     * or document content. Audited (VERIFICATION_SIGNAL_CREATED).
     */
    fun createVerified(
        entityType: String,
        entityId: UUID,
        signalType: String,
        evidence: Map<String, String>,
        provider: String? = null,
    ): UUID
}
