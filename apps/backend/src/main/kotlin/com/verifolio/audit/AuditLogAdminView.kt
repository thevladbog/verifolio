package com.verifolio.audit

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Optional filters for the admin audit-log viewer (spec §Audit-log viewer). All null = unfiltered.
 * `actorType`/`entityType` are exact matches; `action` is a prefix match (LIKE-escaped);
 * `from`/`to` bound `created_at` (inclusive). There is no region filter — `audit_event` has no
 * region column; the cell IS the region (cell-scoped, physically isolated).
 */
data class AuditFilters(
    val actorType: String? = null,
    val action: String? = null,
    val entityType: String? = null,
    val from: OffsetDateTime? = null,
    val to: OffsetDateTime? = null,
)

/**
 * One audit row for the viewer. Metadata is IDs/counts-only by construction (no PII) so it is
 * returned as-is. `ip_hash`/`user_agent_hash` are NEVER surfaced.
 */
data class AuditLogRow(
    val id: UUID,
    val createdAt: OffsetDateTime,
    val actorType: String,
    val actorId: String?,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val metadata: Map<String, Any?>,
)

/** A keyset page of audit rows with an opaque cursor for the next page (null = last page). */
data class AuditPage(
    val items: List<AuditLogRow>,
    val nextCursor: String?,
)

/**
 * A CSV export payload with an explicit truncation signal. [truncated] is true when [rowCount]
 * reached the export cap (10_000) — i.e. rows beyond the cap were silently omitted — so the caller
 * can surface this to admins (response header + audit metadata) rather than let a partial export
 * masquerade as complete. [rowCount] excludes the header line.
 */
data class CsvExport(
    val bytes: ByteArray,
    val truncated: Boolean,
    val rowCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CsvExport) return false
        return truncated == other.truncated && rowCount == other.rowCount && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + truncated.hashCode()
        result = 31 * result + rowCount
        return result
    }
}

/**
 * Audit-owned read model for the admin audit-log viewer (spec §Audit-log viewer). The audit module
 * owns `audit_event`; the admin module reads through this API rather than touching the table
 * directly (module boundary). Reads are cell-scoped (no region column — see [AuditFilters]) and
 * NEVER return `ip_hash`/`user_agent_hash`.
 */
interface AuditLogAdminView {

    /** Keyset-cursor page (50/page) of audit rows, newest-first (created_at, id DESC), filtered. */
    fun list(filters: AuditFilters, cursor: String?): AuditPage

    /**
     * CSV export (`createdAt,actorType,actorId,action,entityType,entityId` — NOT metadata/hashes),
     * same filters, newest-first, capped at 10_000 rows. Returns a [CsvExport] carrying the row count
     * and an explicit `truncated` flag (true when the cap was hit) so the caller can signal a partial
     * export to admins.
     */
    fun exportCsv(filters: AuditFilters): CsvExport
}
