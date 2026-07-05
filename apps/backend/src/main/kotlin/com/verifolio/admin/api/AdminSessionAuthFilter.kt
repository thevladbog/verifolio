package com.verifolio.admin.api

import com.verifolio.admin.application.AdminSessions
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Resolves the `verifolio_admin_session` cookie to an [com.verifolio.admin.AdminActor] principal
 * (mirrors identity SessionAuthFilter). Registered only on the admin SecurityFilterChain
 * (@Order(1), securityMatcher on the admin path prefix), so an admin session is never resolved on the
 * user chain — isolation is by chain, not by shouldNotFilter here.
 *
 * The permitAll pre-session endpoints (the magic-link request/consume and the MFA steps) are skipped:
 * they carry no admin session and reading a stray cookie there would be pointless. Everything else
 * under the admin path prefix requires the authentication this filter sets (anyRequest authenticated).
 */
@Component
internal class AdminSessionAuthFilter(private val adminSessions: AdminSessions) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath
        return path.startsWith("/api/v1/admin/auth/magic-links") ||
            path.startsWith("/api/v1/admin/auth/mfa/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawToken = request.cookies?.firstOrNull { it.name == AdminSessionCookie.SESSION }?.value
        if (rawToken != null && SecurityContextHolder.getContext().authentication == null) {
            adminSessions.resolve(rawToken)?.let { actor ->
                // Authorities stay empty: the permission gate is a service-level AdminAuthorization
                // (Task 4), not URL-role based. The principal is the AdminActor for @AuthenticationPrincipal.
                val auth = UsernamePasswordAuthenticationToken(actor, null, emptyList())
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }
}
