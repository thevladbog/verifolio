package com.verifolio.verification.api

import com.verifolio.platform.ApiException
import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.TokenHasher
import com.verifolio.platform.web.ApiError
import com.verifolio.verification.application.PublicVerificationPageService
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/verification-pages")
internal class PublicVerificationController(
    private val pageService: PublicVerificationPageService,
    private val hasher: TokenHasher,
    @Qualifier("verificationPageIpLimiter") private val ipLimiter: SlidingWindowRateLimiter,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "404", description = "Unknown, revoked, or expired link", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "429", description = "Rate limited", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{token}")
    fun page(@PathVariable token: String, request: HttpServletRequest): VerificationPageResponse {
        rateLimit(request)
        return pageService.page(token, ipHash(request), userAgentHash(request))
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Short-lived presigned link to the pinned PDF"),
        ApiResponse(responseCode = "404", description = "Unknown, revoked, or expired link", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "429", description = "Rate limited", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{token}/download-url")
    fun downloadUrl(@PathVariable token: String, request: HttpServletRequest): PublicDownloadLinkResponse {
        rateLimit(request)
        return pageService.downloadUrl(token, ipHash(request), userAgentHash(request))
    }

    private fun rateLimit(request: HttpServletRequest) {
        val key = request.remoteAddr ?: "unknown"
        if (!ipLimiter.tryAcquire(key)) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests")
        }
    }

    private fun ipHash(request: HttpServletRequest): String? = request.remoteAddr?.let { hasher.hash(it) }

    private fun userAgentHash(request: HttpServletRequest): String? =
        request.getHeader("User-Agent")?.let { hasher.hash(it) }
}
