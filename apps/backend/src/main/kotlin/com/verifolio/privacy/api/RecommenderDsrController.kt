package com.verifolio.privacy.api

import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.web.ApiError
import com.verifolio.privacy.application.DataSubjectRequestService
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Account-less recommender DSR channel (public, CSRF-exempt like invitations). Intake always
 * answers 202 (anti-enumeration); verification consumes an emailed 6-digit code and, for
 * CONSENT_WITHDRAWAL, executes the withdrawal immediately.
 */
@RestController
@RequestMapping("/api/v1/privacy/recommender-requests")
internal class RecommenderDsrController(
    private val service: DataSubjectRequestService,
    @Qualifier("dsrRecommenderIpLimiter") private val ipLimiter: SlidingWindowRateLimiter,
) {

    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Accepted (no match/enumeration signal is leaked)"),
        ApiResponse(responseCode = "400", description = "Validation failed", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping
    fun intake(
        @Valid @RequestBody body: RecommenderDataRequestRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        // Per-IP throttle; a throttled caller still gets 202 (no state oracle).
        if (ipLimiter.tryAcquire(request.remoteAddr ?: "unknown")) {
            service.submitRecommenderRequest(body.email!!)
        }
        return ResponseEntity.accepted().build()
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Code accepted; consent withdrawal executed or request recorded"),
        ApiResponse(responseCode = "400", description = "Invalid or expired code", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Request not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Request already verified or terminal", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/{id}/verify")
    fun verify(
        @PathVariable id: UUID,
        @Valid @RequestBody body: RecommenderDsrVerifyRequest,
    ): RecommenderDsrVerifyResponse =
        service.verifyRecommenderRequest(
            dsrId = id,
            code = body.code!!,
            type = body.type!!,
            referenceRequestId = body.referenceRequestId?.let { UUID.fromString(it) },
        )
}
