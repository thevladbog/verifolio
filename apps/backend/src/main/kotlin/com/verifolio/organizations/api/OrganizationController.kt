package com.verifolio.organizations.api

import com.verifolio.identity.AuthenticatedUser
import com.verifolio.organizations.OrganizationView
import com.verifolio.organizations.application.OrganizationQueryService
import com.verifolio.platform.web.ApiError
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/organizations")
internal class OrganizationController(
    private val organizationQueryService: OrganizationQueryService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Invalid cursor", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping
    fun listOrganizations(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam query: String? = null,
        @RequestParam cursor: String? = null,
    ): OrganizationListResponse = organizationQueryService.list(query, cursor)

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "No verified organization for domain", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/lookup")
    fun lookupOrganization(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam domain: String,
    ): OrganizationView = organizationQueryService.lookupByDomain(domain)

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Organization not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{id}")
    fun getOrganization(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): OrganizationView = organizationQueryService.getById(id)
}
