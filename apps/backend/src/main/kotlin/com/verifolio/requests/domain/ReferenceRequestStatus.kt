package com.verifolio.requests.domain

/** Canonical state machine — status transition table in docs/WORKFLOWS.md. */
enum class ReferenceRequestStatus {
    CREATED, SENT, OPENED, IN_PROGRESS, SUBMITTED, NEEDS_REVIEW,
    CORRECTION_REQUESTED, COMPLETED, DECLINED, EXPIRED, CANCELLED;

    val terminal: Boolean
        get() = this == COMPLETED || this == DECLINED || this == EXPIRED || this == CANCELLED

    fun canTransitionTo(target: ReferenceRequestStatus): Boolean = when (target) {
        CREATED -> false
        SENT -> this == CREATED
        OPENED -> this == SENT
        IN_PROGRESS -> this == OPENED || this == CORRECTION_REQUESTED
        SUBMITTED -> this == IN_PROGRESS
        NEEDS_REVIEW -> this == SUBMITTED
        CORRECTION_REQUESTED -> this == NEEDS_REVIEW
        COMPLETED -> this == NEEDS_REVIEW
        DECLINED -> this == SENT || this == OPENED || this == IN_PROGRESS
        EXPIRED -> this == SENT || this == OPENED || this == IN_PROGRESS || this == CORRECTION_REQUESTED
        CANCELLED -> !terminal
    }
}
