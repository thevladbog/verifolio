package com.verifolio.admin.api

import com.verifolio.platform.web.ApiError
import com.verifolio.platform.web.CsrfHandlerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter
import tools.jackson.databind.ObjectMapper

/**
 * Admin SecurityFilterChain (spec §Admin SecurityFilterChain), fully isolated from the user chain.
 *
 * @Order(1) with a securityMatcher on the admin path prefix means this chain owns every admin request
 * and is consulted before the identity chain (@Order(2), no securityMatcher). Because the admin
 * session is resolved only by [AdminSessionAuthFilter] (registered here, not on the user chain) and
 * the user session only by SessionAuthFilter (on the user chain), an admin credential can never
 * authenticate a user endpoint and a user credential can never authenticate an admin endpoint.
 *
 * CSRF, cookie, exception-handling behaviour mirror the user chain (same XSRF-TOKEN mechanism, same
 * eager handler, same inline JSON 401/403). The pre-session endpoints (magic-link request/consume and
 * the MFA steps) are permitAll + CSRF-exempt: they hold no session, and the MFA endpoints are gated by
 * the `verifolio_admin_pending` cookie which the controller reads and validates directly (no separate
 * pending filter — see AdminAuthController). Everything else requires an admin session + CSRF.
 */
@Configuration
internal class AdminSecurityConfig(
    private val adminSessionAuthFilter: AdminSessionAuthFilter,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    @Order(1)
    fun adminFilterChain(http: HttpSecurity): SecurityFilterChain {
        val csrfHandler = CsrfHandlerFactory.eagerCookieHandler()

        http
            .securityMatcher("/api/v1/admin/**")
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf {
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                it.csrfTokenRequestHandler(csrfHandler)
                // Pre-session, cookie-gated entry points: the magic-link request/consume and the
                // pending-cookie-gated MFA steps. Post-session admin mutations keep CSRF.
                it.ignoringRequestMatchers(
                    "/api/v1/admin/auth/magic-links",
                    "/api/v1/admin/auth/magic-links/consume",
                    "/api/v1/admin/auth/mfa/**",
                )
            }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/api/v1/admin/auth/magic-links",
                    "/api/v1/admin/auth/magic-links/consume",
                    "/api/v1/admin/auth/mfa/**",
                ).permitAll()
                it.anyRequest().authenticated()
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            // Resolve the admin session before CsrfFilter so a CSRF failure for an authenticated
            // admin yields 403 (AccessDenied), matching the user chain's rationale.
            .addFilterBefore(adminSessionAuthFilter, CsrfFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.contentType = "application/json"
                    response.writer.write(objectMapper.writeValueAsString(ApiError("UNAUTHORIZED", "Authentication required")))
                }
                it.accessDeniedHandler { _, response, _ ->
                    response.status = 403
                    response.contentType = "application/json"
                    response.writer.write(objectMapper.writeValueAsString(ApiError("FORBIDDEN", "Access denied")))
                }
            }
        return http.build()
    }
}
