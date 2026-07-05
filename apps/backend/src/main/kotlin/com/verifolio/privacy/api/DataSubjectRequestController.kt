package com.verifolio.privacy.api

import com.verifolio.identity.AuthenticatedUser
import com.verifolio.platform.web.ApiError
import com.verifolio.privacy.application.DataSubjectRequestService
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Account-holder DSR channel (session-scoped). */
@RestController
@RequestMapping("/api/v1/privacy/data-subject-requests")
internal class DataSubjectRequestController(
    private val service: DataSubjectRequestService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Request received (RECEIVED)"),
        ApiResponse(responseCode = "400", description = "Validation failed", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Missing CSRF token", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Type not applicable to the account-holder channel (e.g. consent withdrawal)", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping
    fun submit(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody body: CreateDataSubjectRequestRequest,
    ): ResponseEntity<DataSubjectRequestResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(user, body))

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Invalid cursor", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping
    fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam cursor: String? = null,
    ): DataSubjectRequestListResponse = service.list(user, cursor)
}
