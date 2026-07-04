package com.verifolio.identity.api

import com.verifolio.identity.application.AuthenticatedUser
import com.verifolio.identity.application.SessionService
import com.verifolio.identity.domain.TokenHasher
import com.verifolio.identity.application.MagicLinkService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val magicLinkService: MagicLinkService,
    private val sessionService: SessionService,
    private val hasher: TokenHasher,
) {

    @PostMapping("/magic-links")
    fun requestMagicLink(
        @Valid @RequestBody body: MagicLinkRequest,
        request: HttpServletRequest,
    ): ResponseEntity<MessageResponse> {
        magicLinkService.requestMagicLink(body.email, ipHash(request), userAgentHash(request))
        // Identical response whether or not an account exists (anti-enumeration).
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(MessageResponse("If the address is valid, a sign-in link has been sent."))
    }

    @PostMapping("/sessions")
    fun createSession(
        @Valid @RequestBody body: SessionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<CurrentUserResponse> {
        val created = sessionService.consumeMagicLink(body.token, ipHash(request), userAgentHash(request))
        val cookie = SessionCookie.create(created.rawToken, created.ttlSeconds)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(CurrentUserResponse(created.user.userId.toString(), created.user.email, created.user.region))
    }

    @GetMapping("/sessions/current")
    fun currentSession(@AuthenticationPrincipal user: AuthenticatedUser): CurrentUserResponse =
        CurrentUserResponse(user.userId.toString(), user.email, user.region)

    @DeleteMapping("/sessions/current")
    fun logout(
        @CookieValue(SessionCookie.NAME, required = false) rawToken: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (rawToken != null) {
            sessionService.revoke(rawToken, ipHash(request), userAgentHash(request))
        }
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, SessionCookie.expire().toString())
            .build()
    }

    // ip/user-agent hashes reuse the token pepper for now; a dedicated PII pepper with
    // independent rotation is tracked as a follow-up before production (docs/SECURITY.md).
    internal fun ipHash(request: HttpServletRequest): String? =
        request.remoteAddr?.let { hasher.hash(it) }

    internal fun userAgentHash(request: HttpServletRequest): String? =
        request.getHeader("User-Agent")?.let { hasher.hash(it) }
}
