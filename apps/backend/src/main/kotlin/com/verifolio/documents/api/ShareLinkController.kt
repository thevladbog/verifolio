package com.verifolio.documents.api

import com.verifolio.documents.application.ShareLinkService
import com.verifolio.identity.AuthenticatedUser
import com.verifolio.platform.web.ApiError
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class ShareLinkController(
    private val shareLinks: ShareLinkService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The response contains the raw link URL exactly once"),
        ApiResponse(responseCode = "400", description = "Validation failed", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Missing CSRF token", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Document not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Document has no locked version", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/api/v1/documents/{id}/share-links")
    fun createShareLink(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) body: CreateShareLinkRequest?,
    ): ResponseEntity<ShareLinkCreatedResponse> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(shareLinks.create(user, id, body ?: CreateShareLinkRequest()))

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Document not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/api/v1/documents/{id}/share-links")
    fun listShareLinks(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ShareLinkListResponse = shareLinks.list(user, id)

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Public access stops immediately"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Missing CSRF token", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Share link not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Already revoked", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/api/v1/share-links/{id}/revoke")
    fun revokeShareLink(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ShareLinkResponse = shareLinks.revoke(user, id)
}
