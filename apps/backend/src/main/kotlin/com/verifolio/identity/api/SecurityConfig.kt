package com.verifolio.identity.api

import tools.jackson.databind.ObjectMapper
import com.verifolio.platform.web.ApiError
import com.verifolio.platform.web.CsrfHandlerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter

@Configuration
class SecurityConfig(
    private val sessionAuthFilter: SessionAuthFilter,
    private val recommenderSessionAuthFilter: RecommenderSessionAuthFilter,
    private val objectMapper: ObjectMapper,
) {

    // @Order(2): the admin chain (AdminSecurityConfig @Order(1), securityMatcher /api/v1/admin/**)
    // is consulted first for admin paths; this chain has no securityMatcher and owns all remaining
    // requests. An admin credential can therefore never authenticate on a user endpoint here.
    @Bean
    @Order(2)
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        // Spring Security defers CSRF token loading by default, which means the XSRF-TOKEN
        // cookie is never written unless the token attribute is accessed.  CsrfHandlerFactory
        // creates an XorCsrfTokenRequestAttributeHandler with csrfRequestAttributeName = null,
        // which opts out of deferred loading so the cookie is materialised on every response —
        // required for SPA clients that read the cookie on a GET before issuing a state-changing
        // request.  XorCsrfTokenRequestAttributeHandler also provides BREACH protection by
        // XOR-masking the token value on each response (Security 7 recommended approach).
        // The null is set via a Java helper to avoid the JSpecify @NullMarked compile error in Kotlin.
        val csrfHandler = CsrfHandlerFactory.eagerCookieHandler()

        http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf {
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                it.csrfTokenRequestHandler(csrfHandler)
                // Unauthenticated entry points; session-protected endpoints keep CSRF.
                // Invitation endpoints are token-gated public entry points (pre-session);
                // the session-scoped /api/v1/recommender/** endpoints keep CSRF.
                it.ignoringRequestMatchers(
                    "/api/v1/auth/magic-links",
                    "/api/v1/auth/sessions",
                    "/api/v1/invitations/**",
                    // Public, code-verified recommender DSR channel (anti-enumeration + emailed
                    // code is the auth). The account-holder /api/v1/privacy/** endpoints keep CSRF.
                    "/api/v1/privacy/recommender-requests/**",
                )
            }
            .authorizeHttpRequests {
                it.requestMatchers("/api/v1/auth/magic-links", "/api/v1/auth/sessions").permitAll()
                it.requestMatchers("/api/v1/invitations/**").permitAll()
                // Public recommender DSR channel; the rest of /api/v1/privacy/** is authenticated.
                it.requestMatchers("/api/v1/privacy/recommender-requests/**").permitAll()
                // Public verification pages: tokenized read-only access (docs/PUBLIC_VERIFICATION_PAGE.md).
                it.requestMatchers("/api/v1/verification-pages/**").permitAll()
                // Consent policy texts: public static documents (docs/REGION_POLICIES.md).
                // GET-only endpoint — no CSRF exemption needed.
                it.requestMatchers("/api/v1/consent-texts/**").permitAll()
                it.requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml", "/docs").permitAll()
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
            .addFilterBefore(recommenderSessionAuthFilter, CsrfFilter::class.java)
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
