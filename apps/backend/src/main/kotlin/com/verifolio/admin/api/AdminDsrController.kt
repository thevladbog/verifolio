package com.verifolio.admin.api

import com.verifolio.admin.AdminActor
import com.verifolio.admin.application.AdminAuthorization
import com.verifolio.admin.application.AdminDashboardService
import com.verifolio.admin.domain.AdminPermission
import com.verifolio.audit.AuditService
import com.verifolio.platform.ApiException
import com.verifolio.platform.web.ApiError
import com.verifolio.privacy.DataSubjectRequestAdminView
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Admin DSR review queue (spec §DSR review queue), served on the isolated admin SecurityFilterChain.
 * Every endpoint requires an admin session, a listed [AdminPermission] (checked by
 * [AdminAuthorization] → 403 FORBIDDEN), and is region-scoped to the acting admin's cell. Reads of
 * subject data are audited (`ADMIN_DSR_VIEWED`, IDs only); decisions/executions carry the ADMIN
 * actor id via the privacy admin API (which emits the DSR lifecycle audits).
 */
@RestController
@RequestMapping("/api/v1/admin")
internal class AdminDsrController(
    private val authorization: AdminAuthorization,
    private val dashboard: AdminDashboardService,
    private val dsrAdminView: DataSubjectRequestAdminView,
    private val audit: AuditService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Insufficient permissions", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/dashboard")
    fun dashboard(@AuthenticationPrincipal actor: AdminActor): AdminDashboardResponse {
        authorization.require(actor, AdminPermission.DSR_VIEW)
        val summary = dashboard.dsrSummary(actor.region)
        return AdminDashboardResponse(dsrByStatus = summary.byStatus, dsrPendingTotal = summary.pendingTotal)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Invalid cursor or status", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Insufficient permissions", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/data-subject-requests")
    fun list(
        @AuthenticationPrincipal actor: AdminActor,
        @RequestParam status: String? = null,
        @RequestParam cursor: String? = null,
    ): AdminDsrListResponse {
        authorization.require(actor, AdminPermission.DSR_VIEW)
        val page = dsrAdminView.listForRegion(actor.region, status, cursor)
        audit.record(
            actorType = "ADMIN", actorId = actor.adminId.toString(),
            action = "ADMIN_DSR_VIEWED", entityType = "DATA_SUBJECT_REQUEST",
            metadata = mapOf(
                "region" to actor.region,
                "adminId" to actor.adminId.toString(),
                "dsrIds" to page.items.joinToString(",") { it.id.toString() },
            ),
        )
        return AdminDsrListResponse(page.items.map { AdminDsrItemResponse.from(it) }, page.nextCursor)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Insufficient permissions", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Not found in the admin's region", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/data-subject-requests/{id}")
    fun detail(
        @AuthenticationPrincipal actor: AdminActor,
        @PathVariable id: UUID,
    ): AdminDsrDetailResponse {
        authorization.require(actor, AdminPermission.DSR_VIEW)
        val detail = dsrAdminView.get(id, actor.region)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Data request not found")
        audit.record(
            actorType = "ADMIN", actorId = actor.adminId.toString(),
            action = "ADMIN_DSR_VIEWED", entityType = "DATA_SUBJECT_REQUEST", entityId = id.toString(),
            metadata = mapOf("region" to actor.region, "adminId" to actor.adminId.toString(), "dsrId" to id.toString()),
        )
        return AdminDsrDetailResponse.from(detail)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Approved (RECEIVED/IN_REVIEW → APPROVED)"),
        ApiResponse(responseCode = "403", description = "Insufficient permissions", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Not found in the admin's region", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Illegal state transition", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/data-subject-requests/{id}/approve")
    fun approve(
        @AuthenticationPrincipal actor: AdminActor,
        @PathVariable id: UUID,
    ): AdminDsrStatusResponse {
        authorization.require(actor, AdminPermission.DSR_DECIDE)
        val updated = dsrAdminView.approve(id, actor.region, actor.adminId)
        return AdminDsrStatusResponse(updated.id.toString(), updated.status)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Rejected"),
        ApiResponse(responseCode = "403", description = "Insufficient permissions", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Not found in the admin's region", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Illegal state transition", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/data-subject-requests/{id}/reject")
    fun reject(
        @AuthenticationPrincipal actor: AdminActor,
        @PathVariable id: UUID,
        @Valid @RequestBody body: AdminDsrRejectRequest,
    ): AdminDsrStatusResponse {
        authorization.require(actor, AdminPermission.DSR_DECIDE)
        val updated = dsrAdminView.reject(id, actor.region, actor.adminId, body.notes)
        return AdminDsrStatusResponse(updated.id.toString(), updated.status)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Executed"),
        ApiResponse(responseCode = "403", description = "Insufficient permissions", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Not found in the admin's region", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(
            responseCode = "409",
            description = "Illegal state, or EXECUTION_NOT_AUTOMATED for a type without an automated executor yet",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    @PostMapping("/data-subject-requests/{id}/execute")
    fun execute(
        @AuthenticationPrincipal actor: AdminActor,
        @PathVariable id: UUID,
    ): AdminDsrStatusResponse {
        authorization.require(actor, AdminPermission.DSR_EXECUTE)
        val updated = dsrAdminView.execute(id, actor.region, actor.adminId)
        return AdminDsrStatusResponse(updated.id.toString(), updated.status)
    }
}
