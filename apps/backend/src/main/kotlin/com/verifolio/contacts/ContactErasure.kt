package com.verifolio.contacts

import java.util.UUID

/**
 * Public API of the contacts module for account-deletion erasure of a subject's owned
 * `recommender_contact` rows (docs/PRIVACY_AND_DATA_CLASSIFICATION.md erasure model; the
 * account-deletion matrix in the privacy/DSR design is normative).
 *
 * Called by the privacy module as a step of the account-holder DELETION executor. Owns
 * exactly the contacts-side rows: the rows are RETAINED (RESTRICT FKs from requests/consents
 * hold) but stripped of PII. Never touches any other module's tables.
 */
interface ContactErasure {
    /**
     * Anonymizes every `recommender_contact` owned by [ownerProfileId]: `name` → a tombstone
     * placeholder ("Deleted contact", the column is NOT NULL), `email` → null,
     * `company_name`/`company_domain`/`title` → null; `relationship_type` and the row are
     * kept (FK integrity). Returns the number of contact rows anonymized. Idempotent:
     * re-running sets the same values and returns the same count.
     */
    fun eraseForOwner(ownerProfileId: UUID): Int
}
