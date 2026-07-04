package com.verifolio.platform.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.ForwardedHeaderFilter

@Configuration
class WebConfig {
    // Behind a reverse proxy, remoteAddr must reflect the client (per-IP rate limiting).
    // Assumes the proxy strips untrusted Forwarded/X-Forwarded-* headers from client requests.
    @Bean
    fun forwardedHeaderFilter() = ForwardedHeaderFilter()
}
