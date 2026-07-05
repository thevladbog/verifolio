package com.verifolio.privacy

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A single DSR queue row for the admin console. Carries NO letter/answer/upload content — the
 * data_subject_request row holds none anyway; it is metadata about the request (type, status,
 * subject email, SLA due date). Displayed only to authenticated, permission-gated admins and every
 * read is audited (`ADMIN_DSR_VIEWED`).
 */
data class DsrAdminItem(
    val id: UUID,
    val type: String,
    val status: String,
    val subjectEmail: String,
    val dueAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val resolutionNotes: String?,
)

/** A keyset page of DSR admin items with an opaque cursor for the next page (null = last page). */
data class DsrAdminPage(
    val items: List<DsrAdminItem>,
    val nextCursor: String?,
)

/** Full detail for one DSR (still metadata only — same fields plus region/verifiedAt/updatedAt). */
data class DsrAdminDetail(
    val id: UUID,
    val type: String,
    val status: String,
    val subjectEmail: String,
    val region: String,
    val dueAt: OffsetDateTime,
    val verifiedAt: OffsetDateTime?,
    val resolutionNotes: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?,
)

/**
 * Privacy-owned admin read model for the DSR review queue (spec §DSR review queue). The privacy
 * module owns the data_subject_request table; the admin module reads through this API rather than
 * touching the table directly (module boundary). All reads are region-scoped — an admin sees only
 * their own cell's DSRs (cell isolation is physical, but the query filters by region defensively).
 */
interface DataSubjectRequestAdminView {

    /** Keyset-cursor page (50/page) of the [region]'s DSRs, newest-first, optionally filtered by status. */
    fun listForRegion(region: String, status: String?, cursor: String?): DsrAdminPage

    /** One DSR by id, scoped to [region]; null if it does not exist or belongs to another region. */
    fun get(id: UUID, region: String): DsrAdminDetail?

    /** Count of DSRs in [region] grouped by status name (only statuses with a non-zero count). */
    fun countByStatus(region: String): Map<String, Int>

    /** Count of non-terminal ("pending", i.e. not EXECUTED/REJECTED) DSRs in [region]. */
    fun pendingCount(region: String): Int

    /**
     * Admin decision/execution APIs (spec §DSR review queue). Each is region-scoped — a DSR in
     * another region 404s — and records the acting admin ([adminActorId]) on the DSR lifecycle audit.
     * `execute` on a not-yet-automated type surfaces 409 `EXECUTION_NOT_AUTOMATED`.
     */
    fun approve(id: UUID, region: String, adminActorId: UUID): DsrAdminDetail

    fun reject(id: UUID, region: String, adminActorId: UUID, notes: String?): DsrAdminDetail

    fun execute(id: UUID, region: String, adminActorId: UUID): DsrAdminDetail
}
