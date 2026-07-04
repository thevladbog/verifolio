package com.verifolio.requests.api

import com.verifolio.identity.AuthenticatedUser
import com.verifolio.platform.web.ApiError
import com.verifolio.requests.application.ReferenceRequestService
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reference-requests")
internal class ReferenceRequestController(
    private val service: ReferenceRequestService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Invalid cursor or status filter", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping
    fun listReferenceRequests(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam cursor: String? = null,
        @RequestParam status: String? = null,
    ): ReferenceRequestListResponse = service.list(user, cursor, status)

    @ApiResponses(
        ApiResponse(responseCode = "201"),
        ApiResponse(responseCode = "400", description = "Validation failed or verbal-consent attestation missing", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Missing CSRF token", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Contact not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping
    fun createReferenceRequest(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody body: CreateReferenceRequestRequest,
    ): ResponseEntity<ReferenceRequestResponse> {
        val created = service.create(user, body)
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Missing CSRF token", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Reference request or contact not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Not in CREATED status, expired, or attestation missing", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "429", description = "Recommender email rate limit exceeded", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/{id}/send")
    fun sendReferenceRequest(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ReferenceRequestResponse = service.send(user, id)

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Reference request not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{id}")
    fun getReferenceRequest(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ReferenceRequestResponse = service.get(user, id)
}
