package com.verifolio.verification

import java.time.OffsetDateTime
import java.util.UUID

data class SignalView(
    val signalType: String,
    val status: String,
    val verifiedAt: OffsetDateTime?,
)

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

    /** VERIFIED signals for one entity — the read model for display surfaces. */
    fun listVerified(entityType: String, entityId: UUID): List<SignalView>

    /**
     * Flips VERIFIED signals of the given type on the entity to REVOKED; returns the
     * number flipped. Audits VERIFICATION_SIGNAL_UPDATED per row.
     */
    fun markRevoked(entityType: String, entityId: UUID, signalType: String): Int

    /**
     * Flips VERIFIED signals to EXPIRED (catalog semantics: natural expiry, not
     * revocation); returns the number flipped. Audits VERIFICATION_SIGNAL_UPDATED.
     */
    fun markExpired(entityType: String, entityId: UUID, signalType: String): Int
}
