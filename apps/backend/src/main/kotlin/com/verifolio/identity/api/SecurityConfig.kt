package com.verifolio.identity.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.verifolio.platform.web.ApiError
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler

@Configuration
class SecurityConfig(
    private val sessionAuthFilter: SessionAuthFilter,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        // Spring Security 6 defers CSRF token loading by default, which means the XSRF-TOKEN
        // cookie is never written unless the token attribute is accessed.  Setting
        // csrfRequestAttributeName = null switches to the plain (non-deferred) handler so the
        // cookie is materialised on every response — required for SPA clients that read the
        // cookie on a GET before issuing a state-changing request.
        val csrfHandler = CsrfTokenRequestAttributeHandler().apply { setCsrfRequestAttributeName(null) }

        http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf {
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                it.csrfTokenRequestHandler(csrfHandler)
                // Unauthenticated entry points; session-protected endpoints keep CSRF.
                it.ignoringRequestMatchers("/api/v1/auth/magic-links", "/api/v1/auth/sessions")
            }
            .authorizeHttpRequests {
                it.requestMatchers("/api/v1/auth/magic-links", "/api/v1/auth/sessions").permitAll()
                it.requestMatchers("/v3/api-docs/**", "/docs").permitAll()
                // Logout is idempotent: even an unauthenticated DELETE returns 204 with an
                // expiring cookie. CSRF protection still applies (not in ignoringRequestMatchers).
                it.requestMatchers(HttpMethod.DELETE, "/api/v1/auth/sessions/current").permitAll()
                it.anyRequest().authenticated()
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            // Place session resolution before CsrfFilter so that the user is already
            // authenticated when CSRF validation runs.  This ensures a CSRF failure for an
            // authenticated user returns 403 (AccessDenied) rather than 401 (auth entry point),
            // which is the correct and testable behaviour.
            .addFilterBefore(sessionAuthFilter, CsrfFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.contentType = "application/json"
                    response.writer.write(objectMapper.writeValueAsString(ApiError("UNAUTHORIZED", "Authentication required")))
                }
                // Write 403 directly (no sendError) so Tomcat does not dispatch to /error.
                // AccessDeniedHandlerImpl uses sendError(403) which triggers an internal error
                // dispatch; that second request is anonymous (OncePerRequestFilter skips error
                // dispatches) and our /error path is not permitted, so ExceptionTranslationFilter
                // would convert the 403 into a 401.  Writing the response inline avoids that.
                // CsrfFilter shares this same handler via the HTTP security shared-object, so
                // CSRF failures also produce 403 rather than being re-routed to 401.
                it.accessDeniedHandler { _, response, _ ->
                    response.status = 403
                    response.contentType = "application/json"
                    response.writer.write(objectMapper.writeValueAsString(ApiError("FORBIDDEN", "Access denied")))
                }
            }
        return http.build()
    }
}
