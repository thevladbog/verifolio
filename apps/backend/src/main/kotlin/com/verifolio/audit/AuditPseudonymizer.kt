package com.verifolio.audit

/**
 * Public API of the audit module for account-deletion pseudonymization (the account-deletion
 * matrix in the privacy/DSR design is normative; docs/AUDIT_EVENTS.md).
 *
 * Called by the privacy module as a step of the account-holder DELETION executor. Audit rows
 * are the immutable processing trail: they are NEVER deleted. On erasure of the acting subject
 * their `actor_id` (a text of the subject's user-account id) is nulled so the row survives as
 * an anonymous processing record.
 */
interface AuditPseudonymizer {
    /**
     * Nulls `audit_event.actor_id` on every row where it equals [actorId] (the subject's
     * user-account id string). Rows are retained; only the actor reference is removed. Returns
     * the number of rows pseudonymized. Idempotent: a re-run matches nothing and returns 0.
     */
    fun pseudonymizeActor(actorId: String): Int
}
