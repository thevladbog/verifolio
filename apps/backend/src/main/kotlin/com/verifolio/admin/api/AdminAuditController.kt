package com.verifolio.admin.api

import com.verifolio.admin.AdminActor
import com.verifolio.admin.application.AdminAuditService
import com.verifolio.admin.application.AdminAuthorization
import com.verifolio.admin.domain.AdminPermission
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

/**
 * Admin audit-log viewer (spec §Audit-log viewer), served on the isolated admin SecurityFilterChain.
 * Read-only. List requires `AUDIT_VIEW` (L2+); CSV export requires `AUDIT_EXPORT` (SUPERADMIN) —
 * both checked by [AdminAuthorization] → 403 FORBIDDEN. The audit_event table has no region column
 * (cell = region, physically isolated). Rows NEVER carry `ip_hash`/`user_agent_hash`. Every read
 * (list + export) is itself audited (`ADMIN_AUDIT_LOG_VIEWED`) — this intentionally creates
 * audit-of-audit rows. Orchestration (filter parsing, fetching, self-audit) lives in
 * [AdminAuditService]; the controller only maps HTTP request/response.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
internal class AdminAuditController(
    private val authorization: AdminAuthorization,
    private val auditService: AdminAuditService,
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
        val filters = auditService.parseFilters(actorType, action, entityType, from, to)
        val page = auditService.list(filters, cursor, actor)
        return AdminAuditLogListResponse(page.items.map { AdminAuditLogResponse.from(it) }, page.nextCursor)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "CSV attachment (text/csv); X-Export-Truncated flags a capped export"),
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
        val filters = auditService.parseFilters(actorType, action, entityType, from, to)
        val export = auditService.exportCsv(filters, actor)
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType("text/csv")
            add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-logs.csv\"")
            // Explicit truncation signal: the CSV body stays clean; admins read the flag from the header.
            add("X-Export-Truncated", export.truncated.toString())
        }
        return ResponseEntity(export.bytes, headers, HttpStatus.OK)
    }
}
