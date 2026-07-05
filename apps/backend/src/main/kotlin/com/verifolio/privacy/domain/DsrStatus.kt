package com.verifolio.privacy.domain

/**
 * DSR lifecycle (privacy/DSR design §Lifecycle). CONSENT_WITHDRAWAL runs RECEIVED→EXECUTED in
 * one chain (hybrid execution); the manual types stay RECEIVED until an ops/admin transition.
 * The transition matrix mirrors the ReferenceRequestStatus pattern.
 */
enum class DsrStatus {
    RECEIVED,
    IN_REVIEW,
    APPROVED,
    EXECUTED,
    REJECTED;

    val terminal: Boolean get() = this == EXECUTED || this == REJECTED

    fun canTransitionTo(target: DsrStatus): Boolean = target in transitions

    private val transitions: Set<DsrStatus>
        get() = when (this) {
            RECEIVED -> setOf(IN_REVIEW, APPROVED, EXECUTED, REJECTED)
            IN_REVIEW -> setOf(APPROVED, REJECTED)
            APPROVED -> setOf(EXECUTED, REJECTED)
            EXECUTED -> emptySet()
            REJECTED -> emptySet()
        }
}
