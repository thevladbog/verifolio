package com.verifolio.requests.api

import com.verifolio.identity.AuthenticatedUser
import com.verifolio.platform.web.ApiError
import com.verifolio.requests.application.ResponseReviewService
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reference-requests")
internal class ResponseReviewController(
    private val service: ResponseReviewService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Latest submitted response with upload metadata"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Reference request not found or no submitted response yet", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{id}/response")
    fun getSubmittedResponse(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): SubmittedResponseView = service.getSubmittedResponse(user, id)
}
