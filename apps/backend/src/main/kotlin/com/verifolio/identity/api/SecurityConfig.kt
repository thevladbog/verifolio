package com.verifolio.identity.api

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository

@Configuration
class SecurityConfig(private val sessionAuthFilter: SessionAuthFilter) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf {
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // Unauthenticated entry points; session-protected endpoints keep CSRF.
                it.ignoringRequestMatchers("/api/v1/auth/magic-links", "/api/v1/auth/sessions")
            }
            .authorizeHttpRequests {
                it.requestMatchers("/api/v1/auth/magic-links", "/api/v1/auth/sessions").permitAll()
                it.requestMatchers("/v3/api-docs/**", "/docs").permitAll()
                it.anyRequest().authenticated()
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .addFilterBefore(sessionAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.contentType = "application/json"
                    response.writer.write("""{"code":"UNAUTHORIZED","message":"Authentication required","details":{}}""")
                }
            }
        return http.build()
    }
}
