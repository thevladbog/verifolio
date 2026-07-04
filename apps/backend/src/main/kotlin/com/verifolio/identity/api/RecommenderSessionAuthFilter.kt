package com.verifolio.identity.api

import com.verifolio.identity.RECOMMENDER_SESSION_COOKIE
import com.verifolio.identity.RecommenderSessions
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RecommenderSessionAuthFilter(
    private val recommenderSessions: RecommenderSessions,
) : OncePerRequestFilter() {

    // Scoped to recommender routes only: a recommender session is a request-scoped
    // credential and must never satisfy account-session authorization on user endpoints.
    // SessionAuthFilter skips these routes symmetrically, so a browser holding both
    // cookies still resolves the recommender principal here.
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !isRecommenderRoute(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawToken = request.cookies?.firstOrNull { it.name == RECOMMENDER_SESSION_COOKIE }?.value
        if (rawToken != null && SecurityContextHolder.getContext().authentication == null) {
            recommenderSessions.resolve(rawToken)?.let { actor ->
                val auth = UsernamePasswordAuthenticationToken(actor, null, emptyList())
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        fun isRecommenderRoute(request: HttpServletRequest): Boolean {
            val path = request.servletPath
            return path == "/api/v1/recommender" || path.startsWith("/api/v1/recommender/")
        }
    }
}
