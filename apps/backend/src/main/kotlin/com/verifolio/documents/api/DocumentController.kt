package com.verifolio.documents.api

import com.verifolio.documents.application.DocumentQueryService
import com.verifolio.identity.AuthenticatedUser
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
@RequestMapping("/api/v1/documents")
internal class DocumentController(
    private val documents: DocumentQueryService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Invalid cursor", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping
    fun listDocuments(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam cursor: String? = null,
    ): DocumentListResponse = documents.list(user, cursor)

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Document not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{id}")
    fun getDocument(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): DocumentDetailResponse = documents.get(user, id)

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Short-lived presigned download link"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Document or version not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{id}/versions/{versionNumber}/download-url")
    fun downloadUrl(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @PathVariable versionNumber: Int,
    ): DownloadLinkResponse = documents.downloadUrl(user, id, versionNumber)
}
