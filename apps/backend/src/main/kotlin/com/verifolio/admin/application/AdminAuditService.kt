package com.verifolio.admin.application

import com.verifolio.admin.AdminActor
import com.verifolio.audit.AuditFilters
import com.verifolio.audit.AuditLogAdminView
import com.verifolio.audit.AuditPage
import com.verifolio.audit.AuditService
import com.verifolio.audit.CsvExport
import com.verifolio.platform.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Orchestrates the admin audit-log viewer use case (spec §Audit-log viewer): filter parsing,
 * fetching the page/CSV via the audit-owned read port, and recording the self-audit event
 * (`ADMIN_AUDIT_LOG_VIEWED` — itself an audit-of-audit row). Mirrors [AdminUserService] /
 * [AdminDashboardService] so the REST layer stays thin (HTTP concerns only). Authorization stays in
 * the controller (it is an HTTP/security concern gated per-endpoint by [AdminAuthorization]).
 */
@Service
internal class AdminAuditService(
    private val auditLogAdminView: AuditLogAdminView,
    private val audit: AuditService,
) {

    /** Parses ISO-8601 from/to into [AuditFilters]; a malformed value is a clean 400 rather than a 500. */
    fun parseFilters(
        actorType: String?, action: String?, entityType: String?, from: String?, to: String?,
    ): AuditFilters = try {
        AuditFilters(
            actorType = actorType?.takeIf { it.isNotBlank() },
            action = action?.takeIf { it.isNotBlank() },
            entityType = entityType?.takeIf { it.isNotBlank() },
            from = from?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it) },
            to = to?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it) },
        )
    } catch (e: DateTimeParseException) {
        throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Malformed date; expected ISO-8601")
    }

    /** Fetches a keyset page for [filters]/[cursor] and audits the read (`export=false`). */
    fun list(filters: AuditFilters, cursor: String?, actor: AdminActor): AuditPage {
        val page = auditLogAdminView.list(filters, cursor)
        recordAudit(actor, filters, resultCount = page.items.size, export = false, truncated = false)
        return page
    }

    /** Produces the CSV export for [filters] and audits the read (`export=true`, carrying truncation). */
    fun exportCsv(filters: AuditFilters, actor: AdminActor): CsvExport {
        val export = auditLogAdminView.exportCsv(filters)
        recordAudit(actor, filters, resultCount = export.rowCount, export = true, truncated = export.truncated)
        return export
    }

    /** Names-only filter list for the self-audit metadata — never filter VALUES (could be PII-adjacent). */
    private fun recordAudit(
        actor: AdminActor, filters: AuditFilters, resultCount: Int, export: Boolean, truncated: Boolean,
    ) {
        val filtersApplied = listOfNotNull(
            filters.actorType?.let { "actorType" },
            filters.action?.let { "action" },
            filters.entityType?.let { "entityType" },
            filters.from?.let { "from" },
            filters.to?.let { "to" },
        ).joinToString(",")
        audit.record(
            actorType = "ADMIN", actorId = actor.adminId.toString(),
            action = "ADMIN_AUDIT_LOG_VIEWED", entityType = "AUDIT_EVENT",
            metadata = mapOf(
                "adminId" to actor.adminId.toString(),
                "filtersApplied" to filtersApplied,
                "resultCount" to resultCount.toString(),
                "rowCount" to resultCount.toString(),
                "export" to export.toString(),
                "truncated" to truncated.toString(),
            ),
        )
    }
}
