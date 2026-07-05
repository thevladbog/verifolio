package com.verifolio.identity

import java.util.UUID

/** Cookie carrying the short-lived recommender session (docs/AUTHENTICATION.md). */
const val RECOMMENDER_SESSION_COOKIE = "verifolio_recommender_session"

data class InvitationInfo(val invitationId: UUID, val requestId: UUID, val recommenderEmail: String)

data class RecommenderGrant(
    val rawSessionToken: String,
    val requestId: UUID,
    val recommenderEmail: String,
)

/** Principal for the recommender flow: scoped to exactly one reference request. */
data class RecommenderActor(val requestId: UUID, val email: String)

/**
 * Public API: invitation-token gated entry into the recommender flow.
 * The invitation token is a credential only until email confirmation, where it is
 * consumed single-use; afterwards the recommender session cookie authorizes the flow.
 */
interface InvitationAccess {
    /** Validates without consuming; null if unknown, expired, revoked, or already consumed. */
    fun peek(rawToken: String): InvitationInfo?

    /** Pure identification by hash regardless of token state — for one-click decline paths. */
    fun identify(rawToken: String): InvitationInfo?

    /**
     * Generates and stores a one-time confirmation code (HMAC-hashed); returns the RAW code
     * for the caller to email. Issue-rate limiting is the caller's responsibility.
     */
    fun issueEmailConfirmation(rawToken: String): String

    /**
     * Verifies the code, consumes the invitation token single-use, and mints a recommender
     * session. Raw IP / user-agent values are HMAC-hashed inside identity before storage.
     * Audited (RECOMMENDER_EMAIL_CONFIRMED, INVITATION_TOKEN_CONSUMED).
     */
    fun confirmEmail(rawToken: String, code: String, rawIp: String?, rawUserAgent: String?): RecommenderGrant
}

/** Public API: recommender session lifecycle. */
interface RecommenderSessions {
    fun resolve(rawSessionToken: String): RecommenderActor?

    /** Revokes all active sessions for the request; returns the number revoked. */
    fun revokeForRequest(requestId: UUID): Int
}
