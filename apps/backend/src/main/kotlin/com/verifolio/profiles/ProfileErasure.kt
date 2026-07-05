package com.verifolio.profiles

import java.util.UUID

/**
 * Public API of the profiles module for account-deletion erasure of a subject's
 * `person_profile` PII (docs/PRIVACY_AND_DATA_CLASSIFICATION.md erasure model; the
 * account-deletion matrix in the privacy/DSR design is normative).
 *
 * Called by the privacy module as a step of the account-holder DELETION executor. Owns
 * exactly the profiles-side row: the profile row itself is RETAINED (FK integrity for
 * documents/contacts/consents that reference it) but stripped of PII. Never touches any
 * other module's tables.
 */
interface ProfileErasure {
    /**
     * Anonymizes the subject's profile PII: `display_name` → a tombstone placeholder
     * ("Deleted user", the column is NOT NULL), `legal_name` → null; `preferred_locale` is
     * left as a non-PII UI preference. The row is kept for FK integrity. Idempotent: a
     * profile already carrying the placeholder is re-anonymized to the same values (no
     * observable change). No-op if the user has no profile.
     */
    fun eraseForUser(userId: UUID)
}
