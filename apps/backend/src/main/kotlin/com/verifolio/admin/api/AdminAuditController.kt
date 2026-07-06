package com.verifolio.admin.api

import com.verifolio.admin.AdminActor
import com.verifolio.admin.application.AdminAuthorization
import com.verifolio.admin.domain.AdminPermission
import com.verifolio.audit.AuditFilters
import com.verifolio.audit.AuditLogAdminView
import com.verifolio.audit.AuditService
import com.verifolio.platform.ApiException
import com.verifolio.platform.web.ApiError
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Admin audit-log viewer (spec §Audit-log viewer), served on the isolated admin SecurityFilterChain.
 * Read-only. List requires `AUDIT_VIEW` (L2+); CSV export requires `AUDIT_EXPORT` (SUPERADMIN) —
 * both checked by [AdminAuthorization] → 403 FORBIDDEN. The audit_event table has no region column
 * (cell = region, physically isolated). Rows NEVER carry `ip_hash`/`user_agent_hash`. Every read
 * (list + export) is itself audited (`ADMIN_AUDIT_LOG_VIEWED`) — this intentionally creates
 * audit-of-audit rows.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
internal class AdminAuditController(
    private val authorization: AdminAuthorization,
    private val auditLogAdminView: AuditLogAdminView,
    private val audit: AuditService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Malformed from/to or cursor", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Insufficient permissions", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping
    fun list(
        @AuthenticationPrincipal actor: AdminActor,
        @RequestParam actorType: String? = null,
        @RequestParam action: String? = null,
        @RequestParam entityType: String? = null,
        @RequestParam from: String? = null,
        @RequestParam to: String? = null,
        @RequestParam cursor: String? = null,
    ): AdminAuditLogListResponse {
        authorization.require(actor, AdminPermission.AUDIT_VIEW)
        val filters = parseFilters(actorType, action, entityType, from, to)
        val page = auditLogAdminView.list(filters, cursor)
        recordAudit(actor, filters, resultCount = page.items.size, export = false)
        return AdminAuditLogListResponse(page.items.map { AdminAuditLogResponse.from(it) }, page.nextCursor)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "CSV attachment (text/csv)"),
        ApiResponse(responseCode = "400", description = "Malformed from/to", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Insufficient permissions (needs AUDIT_EXPORT)", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/export")
    fun export(
        @AuthenticationPrincipal actor: AdminActor,
        @RequestParam actorType: String? = null,
        @RequestParam action: String? = null,
        @RequestParam entityType: String? = null,
        @RequestParam from: String? = null,
        @RequestParam to: String? = null,
    ): ResponseEntity<ByteArray> {
        authorization.require(actor, AdminPermission.AUDIT_EXPORT)
        val filters = parseFilters(actorType, action, entityType, from, to)
        val csv = auditLogAdminView.exportCsv(filters)
        // rowCount excludes the header line; capped at 10_000 (truncation is noted via this count).
        val rowCount = (csv.count { it == '\n'.code.toByte() } - 1).coerceAtLeast(0)
        recordAudit(actor, filters, resultCount = rowCount, export = true)
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType("text/csv")
            add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-logs.csv\"")
        }
        return ResponseEntity(csv, headers, HttpStatus.OK)
    }

    /** Names-only filter list for the self-audit metadata — never filter VALUES (could be PII-adjacent). */
    private fun recordAudit(actor: AdminActor, filters: AuditFilters, resultCount: Int, export: Boolean) {
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
                "export" to export.toString(),
            ),
        )
    }

    /** Parses ISO-8601 from/to; a malformed value is a clean 400 rather than a 500. */
    private fun parseFilters(
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
}
