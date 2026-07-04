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
}
