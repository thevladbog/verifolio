package com.verifolio.requests.api

import com.verifolio.identity.RecommenderActor
import com.verifolio.platform.ApiException
import com.verifolio.platform.web.ApiError
import com.verifolio.requests.application.RecommenderFlowService
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/recommender")
internal class RecommenderFlowController(
    private val flow: RecommenderFlowService,
) {

    /** A user session on a recommender endpoint yields a non-RecommenderActor principal → 403. */
    private fun require(actor: RecommenderActor?): RecommenderActor =
        actor ?: throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Recommender session required")

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "No active recommender session", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Not a recommender session", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Request no longer active", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/request")
    fun requestContext(
        @AuthenticationPrincipal actor: RecommenderActor?,
    ): RecommenderRequestContext = flow.context(require(actor))

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "No active recommender session", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Not a recommender session or missing CSRF token", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Consent gate already passed", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/consent")
    fun consent(
        @AuthenticationPrincipal actor: RecommenderActor?,
        @Valid @RequestBody body: ConsentDecisionRequest,
    ): RecommenderStatusResponse =
        RecommenderStatusResponse(flow.consent(require(actor), body).name)

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "No active recommender session", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Not a recommender session or missing CSRF token", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Consent not accepted yet", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PutMapping("/response-draft")
    fun saveDraft(
        @AuthenticationPrincipal actor: RecommenderActor?,
        @Valid @RequestBody body: DraftRequest,
    ): DraftDto = flow.saveDraft(require(actor), body)

    @ApiResponses(
        ApiResponse(responseCode = "201"),
        ApiResponse(responseCode = "400", description = "Missing confirmations or letter text", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "No active recommender session", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "403", description = "Not a recommender session or missing CSRF token", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Wrong status or consent missing", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/responses")
    fun submit(
        @AuthenticationPrincipal actor: RecommenderActor?,
        @Valid @RequestBody body: SubmitResponseRequest,
    ): ResponseEntity<RecommenderStatusResponse> {
        val status = flow.submit(require(actor), body)
        return ResponseEntity.status(HttpStatus.CREATED).body(RecommenderStatusResponse(status.name))
    }
}
