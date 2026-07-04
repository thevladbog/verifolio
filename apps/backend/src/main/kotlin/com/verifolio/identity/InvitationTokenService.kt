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

    /** Revokes all outstanding (unconsumed, unrevoked) tokens for the request. Audited per token. */
    fun revokeForRequest(requestId: UUID): Int
}
