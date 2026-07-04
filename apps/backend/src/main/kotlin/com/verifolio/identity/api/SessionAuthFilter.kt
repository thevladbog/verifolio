package com.verifolio.identity.api

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

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawToken = request.cookies?.firstOrNull { it.name == SessionCookie.NAME }?.value
        if (rawToken != null && SecurityContextHolder.getContext().authentication == null) {
            sessionService.resolve(rawToken)?.let { user ->
                val auth = UsernamePasswordAuthenticationToken(user, rawToken, emptyList())
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }
}
