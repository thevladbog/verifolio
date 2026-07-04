package com.verifolio.identity.api

import org.springframework.http.ResponseCookie
import java.time.Duration

object SessionCookie {
    const val NAME = "verifolio_session"

    fun create(rawToken: String, ttlSeconds: Long): ResponseCookie =
        ResponseCookie.from(NAME, rawToken)
            .httpOnly(true).secure(true).sameSite("Strict").path("/")
            .maxAge(Duration.ofSeconds(ttlSeconds))
            .build()

    fun expire(): ResponseCookie =
        ResponseCookie.from(NAME, "")
            .httpOnly(true).secure(true).sameSite("Strict").path("/")
            .maxAge(Duration.ZERO)
            .build()
}
