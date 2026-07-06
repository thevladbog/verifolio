package com.verifolio.identity

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A lightweight user row for the admin user list (spec §User views). Metadata only — no
 * credentials, tokens, or session hashes. `displayName` is LEFT JOINed from person_profile and may
 * be null (an account without a profile). Displayed only to permission-gated admins; every list read
 * is audited (`ADMIN_USER_LIST_VIEWED`).
 */
data class UserAdminSummary(
    val id: UUID,
    val email: String,
    val displayName: String?,
    val region: String,
    val status: String,
    val createdAt: OffsetDateTime,
)

/** A keyset page of user admin summaries with an opaque cursor for the next page (null = last). */
data class UserAdminPage(
    val items: List<UserAdminSummary>,
    val nextCursor: String?,
)

/** Account metadata for the admin user card — email, region, status, and lifecycle timestamps. */
data class UserAdminAccount(
    val email: String,
    val region: String,
    val status: String,
    val createdAt: OffsetDateTime,
    val deletedAt: OffsetDateTime?,
)

/**
 * One user_session row for the admin card — timestamps only. Deliberately carries NO `ip_hash` or
 * `user_agent_hash` (opaque, non-reversible, and never surfaced to support); device/location is not
 * shown. `lastSeenAt` is nullable because the schema has no last-seen column (always null today).
 */
data class UserAdminSession(
    val createdAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime?,
    val expiresAt: OffsetDateTime,
    val revokedAt: OffsetDateTime?,
)

/** The admin user card: account metadata plus the user's sessions (newest-first, metadata only). */
data class UserAdminCard(
    val account: UserAdminAccount,
    val sessions: List<UserAdminSession>,
)

/**
 * Identity-owned admin read model for the user list + card (spec §User views). The identity module
 * owns user_account + user_session; the admin module reads through this API rather than touching the
 * tables directly (module boundary). All reads are region-scoped — an admin sees only their own
 * cell's users (cell isolation is physical, but the queries filter by region defensively).
 */
interface UserAdminView {

    /**
     * Keyset-cursor page (50/page) of [region]'s users, newest-first (created_at, id DESC).
     * [query] (optional) prefix-matches email OR display_name (ILIKE, LIKE-metacharacters escaped);
     * [status] (optional) filters on the exact user_account.status.
     */
    fun list(region: String, query: String?, status: String?, cursor: String?): UserAdminPage

    /** The card for [userId], scoped to [region]; null if the user does not exist or is foreign-region. */
    fun card(userId: UUID, region: String): UserAdminCard?
}
