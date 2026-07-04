package com.verifolio.identity.api

import com.verifolio.identity.AuthenticatedUser
import com.verifolio.identity.application.SessionService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class SessionAuthFilter(private val sessionService: SessionService) : OncePerRequestFilter() {

    /**
     * Recommender routes are authenticated exclusively by RecommenderSessionAuthFilter;
     * skipping them here means a browser holding both a user and a recommender cookie
     * still completes the invitation flow with the recommender principal.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        RecommenderSessionAuthFilter.isRecommenderRoute(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawToken = request.cookies?.firstOrNull { it.name == SessionCookie.NAME }?.value
        if (rawToken != null && SecurityContextHolder.getContext().authentication == null) {
            sessionService.resolve(rawToken)?.let { user ->
                val auth = UsernamePasswordAuthenticationToken(user, null, emptyList())
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }
}
