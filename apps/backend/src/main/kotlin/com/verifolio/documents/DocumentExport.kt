package com.verifolio.documents

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Per-version metadata for a DSR EXPORT package. Metadata only — the locked content
 * (content_json / rendered_html / PDF bytes) is NEVER included; the subject retains
 * in-app access to it. The sha256 hash proves integrity of the version they hold.
 */
data class VersionExportData(
    val versionNumber: Int,
    val sha256: String,
    val status: String,
    val lockedAt: OffsetDateTime?,
    val retractedAt: OffsetDateTime?,
    val tombstonedAt: OffsetDateTime?,
)

/**
 * Per-document metadata for a DSR EXPORT package.
 */
data class DocumentExportData(
    val documentId: UUID,
    val type: String?,
    val versions: List<VersionExportData>,
)

/**
 * One-way export read port of the documents module. Returns the documents owned by
 * [ownerProfileId] (via document.owner_profile_id) with their version metadata.
 */
interface DocumentExport {
    fun forOwner(ownerProfileId: UUID): List<DocumentExportData>
}
