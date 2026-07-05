package com.verifolio.documents

import java.util.UUID

/**
 * Public API of the documents module for account-deletion erasure of a subject's owned
 * documents (docs/PRIVACY_AND_DATA_CLASSIFICATION.md erasure model; the account-deletion
 * matrix in the privacy/DSR design is normative).
 *
 * Called by the privacy module as a step of the account-holder DELETION executor. Erasure of
 * locked versions goes exclusively through the sanctioned [DocumentTombstone] path (NULL
 * content + TOMBSTONED, retaining sha256/version/lockedAt); this port merely resolves the
 * owner's non-tombstoned versions and drives that path per version.
 */
interface OwnerErasure {
    /**
     * Tombstones every non-tombstoned `document_version` of every document owned by
     * [ownerProfileId] via [DocumentTombstone.tombstone]. Returns the ids of the versions
     * that were tombstoned by this call. Idempotent: already-tombstoned versions are skipped,
     * so a re-run returns an empty list.
     */
    fun tombstoneForOwner(ownerProfileId: UUID): List<UUID>
}
