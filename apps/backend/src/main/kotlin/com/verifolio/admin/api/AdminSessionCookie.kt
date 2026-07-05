package com.verifolio.admin.api

import org.springframework.http.ResponseCookie
import java.time.Duration

/**
 * Admin auth cookies, built exactly like the identity SessionCookie (HttpOnly, Secure,
 * SameSite=Strict, path "/") but on isolated names so an admin credential is never presented
 * on the user chain and vice versa.
 *   - [SESSION] `verifolio_admin_session`: the post-MFA admin session (8h, spec §Flow).
 *   - [PENDING] `verifolio_admin_pending`: the short-lived (5m) pending-MFA cookie carried only
 *     between magic-link consume and the MFA enroll/verify step; never an admin session.
 */
object AdminSessionCookie {
    const val SESSION = "verifolio_admin_session"
    const val PENDING = "verifolio_admin_pending"

    fun session(rawToken: String, ttlSeconds: Long, secure: Boolean): ResponseCookie =
        build(SESSION, rawToken, ttlSeconds, secure)

    fun expireSession(secure: Boolean): ResponseCookie = build(SESSION, "", 0, secure)

    fun pending(rawToken: String, ttlSeconds: Long, secure: Boolean): ResponseCookie =
        build(PENDING, rawToken, ttlSeconds, secure)

    fun expirePending(secure: Boolean): ResponseCookie = build(PENDING, "", 0, secure)

    private fun build(name: String, value: String, ttlSeconds: Long, secure: Boolean): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(true).secure(secure).sameSite("Strict").path("/")
            .maxAge(Duration.ofSeconds(ttlSeconds))
            .build()
}
