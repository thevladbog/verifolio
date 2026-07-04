package com.verifolio.identity.api

import com.verifolio.identity.application.MagicLinkService
import com.verifolio.identity.domain.TokenHasher
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val magicLinkService: MagicLinkService,
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

    // ip/user-agent hashes reuse the token pepper for now; a dedicated PII pepper with
    // independent rotation is tracked as a follow-up before production (docs/SECURITY.md).
    internal fun ipHash(request: HttpServletRequest): String? =
        request.remoteAddr?.let { hasher.hash(it) }

    internal fun userAgentHash(request: HttpServletRequest): String? =
        request.getHeader("User-Agent")?.let { hasher.hash(it) }
}
