package com.verifolio.identity.api;

import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Factory for creating CSRF token request handlers.
 *
 * <p>Spring Security 7 annotated its {@code csrf} package with JSpecify {@code @NullMarked},
 * which makes Kotlin treat {@code setCsrfRequestAttributeName(String)} as non-null. However,
 * passing {@code null} is intentionally supported by the implementation (the Javadoc documents
 * it as the way to opt-out of deferred CSRF token loading). This Java factory avoids the Kotlin
 * compile error since Java ignores JSpecify @NullMarked at the call site.
 */
public final class CsrfHandlerFactory {

    private CsrfHandlerFactory() {}

    /**
     * Creates a {@link CsrfTokenRequestAttributeHandler} with deferred loading disabled.
     *
     * <p>Setting {@code csrfRequestAttributeName = null} forces the CSRF cookie to be written on
     * every response rather than only when the token attribute is first accessed. This is required
     * for SPA clients that read the {@code XSRF-TOKEN} cookie on a GET before issuing a
     * state-changing request. The raw (non-XOR-masked) token is appropriate for
     * {@code CookieCsrfTokenRepository} because the SPA reads the cookie directly and sends
     * the exact value as the {@code X-XSRF-TOKEN} header.
     */
    public static CsrfTokenRequestAttributeHandler eagerCookieHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }
}
