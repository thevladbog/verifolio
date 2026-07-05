package com.verifolio.admin.api

import com.verifolio.admin.AdminActor
import com.verifolio.admin.application.AdminAccounts
import com.verifolio.admin.application.AdminMagicLinks
import com.verifolio.admin.application.AdminMfa
import com.verifolio.admin.application.AdminSessions
import com.verifolio.admin.domain.AdminAccount
import com.verifolio.audit.AuditService
import com.verifolio.notifications.MailPort
import com.verifolio.platform.ApiException
import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.TokenHasher
import com.verifolio.platform.VerifolioProperties
import com.verifolio.platform.web.ApiError
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin authentication endpoints (spec §Flow), served on the isolated admin SecurityFilterChain.
 * The two-factor sequence mints an admin session ONLY after both magic-link and TOTP pass; the
 * pending-MFA cookie between the two steps is read straight from the request here (no dedicated
 * pending filter) and validated by [AdminMfa] (hash lookup, TTL, single-use, attempt cap).
 */
@RestController
@RequestMapping("/api/v1/admin")
internal class AdminAuthController(
    private val magicLinks: AdminMagicLinks,
    private val accounts: AdminAccounts,
    private val mfa: AdminMfa,
    private val sessions: AdminSessions,
    private val mail: MailPort,
    private val audit: AuditService,
    private val hasher: TokenHasher,
    private val props: VerifolioProperties,
    @Qualifier("adminMagicLinkEmailLimiter") private val emailLimiter: SlidingWindowRateLimiter,
    @Qualifier("adminMagicLinkIpLimiter") private val ipLimiter: SlidingWindowRateLimiter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Accepted — identical whether or not an admin exists"),
        ApiResponse(responseCode = "429", description = "Rate limited", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/auth/magic-links")
    fun requestMagicLink(
        @Valid @RequestBody body: AdminMagicLinkRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AdminMessageResponse> {
        val email = body.email.trim().lowercase()
        val ipHash = ipHash(request)
        val emailAllowed = emailLimiter.tryAcquire(email)
        val ipAllowed = ipHash == null || ipLimiter.tryAcquire(ipHash)
        if (!emailAllowed || !ipAllowed) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests, try again later")
        }

        // Anti-enumeration: mint + email only for an ACTIVE admin; the response is identical either way.
        if (accounts.activeByEmail(email) != null) {
            val rawToken = magicLinks.mint(email)
            try {
                mail(email, rawToken)
            } catch (ex: Exception) {
                log.error("Failed to send admin magic-link email (address withheld from logs)", ex)
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(AdminMessageResponse("If the address is an admin, a sign-in link has been sent."))
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Token consumed; MFA branch returned + pending cookie set"),
        ApiResponse(responseCode = "400", description = "Invalid or expired token", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/auth/magic-links/consume")
    fun consume(
        @Valid @RequestBody body: AdminConsumeRequest,
    ): ResponseEntity<AdminConsumeResponse> {
        val account = magicLinks.consume(body.token)
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "TOKEN_INVALID", "The link is invalid or expired")
        val pending = mfa.startPending(account)
        val cookie = AdminSessionCookie.pending(pending.rawToken, pending.ttlSeconds, props.auth.cookieSecure)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(AdminConsumeResponse(pending.state.name))
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "No/expired pending MFA", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/auth/mfa/enrollment")
    fun enrollment(
        @CookieValue(AdminSessionCookie.PENDING, required = false) pending: String?,
    ): AdminEnrollmentResponse {
        val info = mfa.enrollment(requirePending(pending))
        return AdminEnrollmentResponse(info.secretBase32, info.otpauthUri)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Enrolled; admin session cookie set"),
        ApiResponse(responseCode = "400", description = "Invalid code / pending", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/auth/mfa/enroll")
    fun enroll(
        @Valid @RequestBody body: AdminMfaCodeRequest,
        @CookieValue(AdminSessionCookie.PENDING, required = false) pending: String?,
        request: HttpServletRequest,
    ): ResponseEntity<AdminOkResponse> =
        completeMfa(mfa.enroll(requirePending(pending), body.code), request)

    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Challenge passed; admin session cookie set"),
        ApiResponse(responseCode = "400", description = "Invalid code / pending", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/auth/mfa/verify")
    fun verify(
        @Valid @RequestBody body: AdminMfaCodeRequest,
        @CookieValue(AdminSessionCookie.PENDING, required = false) pending: String?,
        request: HttpServletRequest,
    ): ResponseEntity<AdminOkResponse> =
        completeMfa(mfa.verifyChallenge(requirePending(pending), body.code), request)

    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Session revoked (idempotent)"),
        ApiResponse(responseCode = "403", description = "Missing CSRF token", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping("/auth/logout")
    fun logout(
        @CookieValue(AdminSessionCookie.SESSION, required = false) rawToken: String?,
        @AuthenticationPrincipal actor: AdminActor,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (rawToken != null) sessions.revoke(rawToken)
        audit.record(
            actorType = "ADMIN", actorId = actor.adminId.toString(),
            action = "ADMIN_SESSION_REVOKED", entityType = "ADMIN_SESSION",
            metadata = mapOf("region" to props.region, "adminId" to actor.adminId.toString()),
            ipHash = ipHash(request), userAgentHash = userAgentHash(request),
        )
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, AdminSessionCookie.expireSession(props.auth.cookieSecure).toString())
            .build()
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal actor: AdminActor): AdminMeResponse =
        AdminMeResponse(actor.adminId.toString(), actor.email, actor.role.name, actor.region)

    /** Mints the admin session from an MFA-verified [account], sets its cookie, clears pending, audits. */
    private fun completeMfa(account: AdminAccount, request: HttpServletRequest): ResponseEntity<AdminOkResponse> {
        val ipHash = ipHash(request)
        val uaHash = userAgentHash(request)
        val session = sessions.mint(account.id, ipHash, uaHash)
        audit.record(
            actorType = "ADMIN", actorId = account.id.toString(),
            action = "ADMIN_LOGIN_SUCCEEDED", entityType = "ADMIN_ACCOUNT",
            metadata = mapOf("region" to props.region, "adminId" to account.id.toString()),
            ipHash = ipHash, userAgentHash = uaHash,
        )
        audit.record(
            actorType = "ADMIN", actorId = account.id.toString(),
            action = "ADMIN_SESSION_CREATED", entityType = "ADMIN_SESSION",
            metadata = mapOf("region" to props.region, "adminId" to account.id.toString()),
            ipHash = ipHash, userAgentHash = uaHash,
        )
        val sessionCookie = AdminSessionCookie.session(session.rawToken, session.ttlSeconds, props.auth.cookieSecure)
        val clearPending = AdminSessionCookie.expirePending(props.auth.cookieSecure)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
            .header(HttpHeaders.SET_COOKIE, clearPending.toString())
            .body(AdminOkResponse())
    }

    private fun requirePending(pending: String?): String =
        pending ?: throw ApiException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "The code is invalid or expired")

    private fun mail(email: String, rawToken: String) {
        mail.send(
            to = email,
            subject = "Your Verifolio admin sign-in link",
            textBody = "Sign in to the Verifolio admin console: " +
                "${props.auth.frontendBaseUrl}/admin/auth/callback?token=$rawToken\n" +
                "The link is valid for 15 minutes and can be used once.",
        )
    }

    private fun ipHash(request: HttpServletRequest): String? =
        request.remoteAddr?.let { hasher.hash(it) }

    private fun userAgentHash(request: HttpServletRequest): String? =
        request.getHeader("User-Agent")?.let { hasher.hash(it) }
}
