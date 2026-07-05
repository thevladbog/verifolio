package com.verifolio.identity

import java.util.UUID

/**
 * Public API of the identity module for account-deletion erasure of a subject's
 * authentication data (docs/PRIVACY_AND_DATA_CLASSIFICATION.md erasure model; the
 * account-deletion matrix in the privacy/DSR design is normative).
 *
 * Called by the privacy module as a step of the account-holder DELETION executor. Owns
 * exactly the identity-side rows: `user_account` is RETAINED as a tombstone (FK integrity
 * for retained consent/audit), while the live-credential rows (`user_session`,
 * `magic_link_token`) are deleted outright. Never touches any other module's tables.
 */
interface AccountErasure {
    /**
     * Tombstones the subject's account: `user_account.status` → 'DELETED', `deleted_at` →
     * now, `email` → `deleted-<userId>@tombstone.invalid`; deletes every `user_session` for
     * the user and every `magic_link_token` for the account's (pre-erasure) email. Idempotent:
     * an already-DELETED account is a no-op. No-op if the user does not exist.
     */
    fun eraseForUser(userId: UUID)
}
