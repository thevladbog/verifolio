package com.verifolio.documents

import java.util.UUID

/**
 * Public API: recommender retraction / consent withdrawal (docs/WORKFLOWS.md retraction
 * flow, privacy/DSR design §Retraction). Retraction is NOT deletion — the locked content
 * stays readable; only `retracted_at` is stamped so surfaces can render the retracted
 * banner. This is one of the two sanctioned mutations of a locked version (the other is
 * the tombstone path); no other column of a locked version is ever touched here.
 */
interface DocumentRetraction {
    /**
     * Stamps `retracted_at = now` (null → now only; idempotent) on every non-tombstoned
     * version of the request's document. Returns the number of versions affected. Locked
     * content is never modified. Audits RECOMMENDATION_RETRACTED (metadata: requestId, count).
     */
    fun markRetracted(requestId: UUID): Int

    /**
     * Version ids of the request's document(s) — the entities whose verification signals the
     * privacy module revokes as part of a retraction / consent-withdrawal. Read-only; owns the
     * document→version resolution so the privacy module never touches documents-owned tables.
     */
    fun versionIdsForRequest(requestId: UUID): List<UUID>
}
