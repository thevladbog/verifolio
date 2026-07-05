package com.verifolio.identity

import java.time.Duration
import java.util.UUID

/**
 * Public API: single-use recommender invitation tokens (docs/AUTHENTICATION.md).
 * Raw tokens are never stored; the DB keeps only the HMAC hash.
 */
interface InvitationTokenService {
    /** Mints a token bound to the request; returns the RAW token. */
    fun mint(requestId: UUID, recommenderEmail: String, ttl: Duration): String

    /**
     * Revokes outstanding (unconsumed, unrevoked) tokens for the request. Audited per
     * token. [exceptRawToken] excludes one token (matched by hash) — reminder re-minting
     * revokes the previous links after the new one was successfully emailed. The
     * exclusion is by identity, never by timestamps: DB `created_at` defaults use the
     * transaction start time, which is not comparable with mid-transaction JVM clocks.
     */
    fun revokeForRequest(requestId: UUID, exceptRawToken: String? = null): Int
}
