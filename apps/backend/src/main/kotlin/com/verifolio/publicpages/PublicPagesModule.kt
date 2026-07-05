package com.verifolio.publicpages

/**
 * Public pages module: composition layer for the public verification page. Read-only —
 * it assembles the page from the read models of documents (ShareLinkAccess), requests
 * (RequestPublicView), profiles, and verification (signals + display texts), and owns
 * the public `/api/v1/verification-pages` endpoints.
 *
 * This module sits on top of the domain modules so the page can consume all of them
 * without creating dependency cycles (documents/requests write INTO verification; the
 * page must read FROM all three). Nothing may depend on this module.
 * Boundary rules: see docs/MODULES.md and docs/ARCHITECTURE.md.
 */
object PublicPagesModule
