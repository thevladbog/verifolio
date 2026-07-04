package com.verifolio.requests.api

import com.verifolio.identity.RECOMMENDER_SESSION_COOKIE
import com.verifolio.platform.VerifolioProperties
import com.verifolio.platform.web.ApiError
import com.verifolio.requests.application.RecommenderFlowService
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/invitations")
internal class InvitationController(
    private val flow: RecommenderFlowService,
    private val props: VerifolioProperties,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "404", description = "Invitation unknown, expired, consumed, or request terminal", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{token}")
    fun openInvitation(@PathVariable token: String): InvitationPreviewResponse = flow.open(token)

    @ApiResponses(
        ApiResponse(responseCode = "202"),
        ApiResponse(responseCode = "404", description = "Invitation not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "429", description = "Too many codes requested", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/{token}/email-confirmations")
    fun requestEmailConfirmation(@PathVariable token: String): ResponseEntity<Void> {
        flow.requestEmailConfirmation(token)
        return ResponseEntity.accepted().build()
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Recommender session cookie set"),
        ApiResponse(responseCode = "400", description = "Invalid or expired code", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Invitation not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/{token}/confirm-email")
    fun confirmEmail(
        @PathVariable token: String,
        @Valid @RequestBody body: ConfirmEmailRequest,
    ): ResponseEntity<ConfirmEmailResponse> {
        val (grant, status) = flow.confirmEmail(token, body.code, null, null)
        val cookie = ResponseCookie.from(RECOMMENDER_SESSION_COOKIE, grant.rawSessionToken)
            .httpOnly(true)
            .secure(props.auth.cookieSecure)
            .sameSite("Strict")
            .path("/")
            .maxAge(props.auth.recommenderSessionTtl)
            .build()
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(ConfirmEmailResponse(status = status))
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "404", description = "Invitation not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Request already terminal", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/{token}/decline")
    fun decline(@PathVariable token: String): ResponseEntity<Void> {
        flow.declineByToken(token, reason = "declined")
        return ResponseEntity.status(HttpStatus.OK).build()
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "404", description = "Invitation not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "409", description = "Request already terminal", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/{token}/report-abuse")
    fun reportAbuse(@PathVariable token: String): ResponseEntity<Void> {
        flow.declineByToken(token, reason = "abuse_report")
        return ResponseEntity.status(HttpStatus.OK).build()
    }
}
