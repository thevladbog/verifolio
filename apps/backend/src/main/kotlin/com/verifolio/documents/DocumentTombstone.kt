package com.verifolio.documents

import java.util.UUID

/**
 * Public API: tombstoning of a locked version (privacy/DSR design §Tombstoning,
 * PRIVACY_AND_DATA_CLASSIFICATION.md erasure model). The single sanctioned content-erasing
 * mutation of a locked version: the generated PDF and attachment objects are physically
 * deleted via the files module, then `content_json`/`rendered_html` are nulled and the
 * status flips to TOMBSTONED. The integrity anchors — `sha256_hash`, `version_number`,
 * `locked_at` — are RETAINED so the erasure itself remains provable.
 */
interface DocumentTombstone {
    /**
     * Tombstones the version: S3-deletes its generated PDF and attachment file objects
     * (files module — the only path to storage), then nulls the content columns, sets
     * status TOMBSTONED and `tombstoned_at`. Idempotent (already TOMBSTONED → no-op).
     * Audits DOCUMENT_VERSION_TOMBSTONED (metadata: versionId).
     */
    fun tombstone(versionId: UUID)
}
