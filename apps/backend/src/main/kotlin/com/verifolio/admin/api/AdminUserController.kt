package com.verifolio.admin.api

import com.verifolio.admin.AdminActor
import com.verifolio.admin.application.AdminAuthorization
import com.verifolio.admin.application.AdminUserService
import com.verifolio.admin.domain.AdminPermission
import com.verifolio.audit.AuditService
import com.verifolio.identity.UserAdminView
import com.verifolio.platform.ApiException
import com.verifolio.platform.web.ApiError
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Admin user list + card (spec §User views), served on the isolated admin SecurityFilterChain.
 * Read-only: no mutations. Every endpoint requires an admin session, the `USER_VIEW` permission
 * (L2+ — checked by [AdminAuthorization] → 403 FORBIDDEN), and is region-scoped to the acting
 * admin's cell. Every read of user data is audited (`ADMIN_USER_LIST_VIEWED` /
 * `ADMIN_USER_DETAIL_VIEWED`, IDs/counts/filters only — never emails or content).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
internal class AdminUserController(
    private val authorization: AdminAuthorization,
    private val userAdminView: UserAdminView,
    private val userService: AdminUserService,
    private val audit: AuditService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Insufficient permissions", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping
    fun list(
        @AuthenticationPrincipal actor: AdminActor,
        @RequestParam query: String? = null,
        @RequestParam status: String? = null,
        @RequestParam cursor: String? = null,
    ): AdminUserListResponse {
        authorization.require(actor, AdminPermission.USER_VIEW)
        val page = userAdminView.list(actor.region, query, status, cursor)
        // Metadata is IDs/counts/filter-names only — never the query value or any email (would be PII).
        val filtersApplied = listOfNotNull(query?.let { "query" }, status?.let { "status" }).joinToString(",")
        audit.record(
            actorType = "ADMIN", actorId = actor.adminId.toString(),
            action = "ADMIN_USER_LIST_VIEWED", entityType = "USER_ACCOUNT",
            metadata = mapOf(
                "region" to actor.region,
                "adminId" to actor.adminId.toString(),
                "resultCount" to page.items.size.toString(),
                "filtersApplied" to filtersApplied,
            ),
        )
        return AdminUserListResponse(page.items.map { AdminUserListItemResponse.from(it) }, page.nextCursor)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Insufficient permissions", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Not found in the admin's region", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{id}")
    fun detail(
        @AuthenticationPrincipal actor: AdminActor,
        @PathVariable id: UUID,
    ): AdminUserCardResponse {
        authorization.require(actor, AdminPermission.USER_VIEW)
        val card = userService.card(id, actor.region)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found")
        audit.record(
            actorType = "ADMIN", actorId = actor.adminId.toString(),
            action = "ADMIN_USER_DETAIL_VIEWED", entityType = "USER_ACCOUNT", entityId = id.toString(),
            metadata = mapOf("region" to actor.region, "adminId" to actor.adminId.toString(), "userId" to id.toString()),
        )
        return AdminUserCardResponse.from(card)
    }
}
