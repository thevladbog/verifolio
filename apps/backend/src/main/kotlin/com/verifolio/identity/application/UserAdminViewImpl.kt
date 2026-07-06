package com.verifolio.identity.application

import com.verifolio.identity.UserAdminAccount
import com.verifolio.identity.UserAdminCard
import com.verifolio.identity.UserAdminPage
import com.verifolio.identity.UserAdminSession
import com.verifolio.identity.UserAdminSummary
import com.verifolio.identity.UserAdminView
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.jooq.tables.references.USER_SESSION
import com.verifolio.platform.ApiException
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

private const val ADMIN_PAGE_SIZE = 50

/**
 * Identity-owned implementation of the admin user read model. Newest-first keyset pagination
 * (created_at, id) DESC, region-scoped, optional email/display_name prefix search + status filter.
 * Returns metadata-only DTOs — never session ip/ua hashes.
 */
@Service
internal class UserAdminViewImpl(private val dsl: DSLContext) : UserAdminView {

    @Transactional(readOnly = true)
    override fun list(region: String, query: String?, status: String?, cursor: String?): UserAdminPage {
        val ua = USER_ACCOUNT
        val pp = PERSON_PROFILE

        val searchCondition = if (!query.isNullOrBlank()) {
            val prefix = escapeLike(query.trim().lowercase()) + "%"
            DSL.lower(ua.EMAIL).like(prefix, '\\').or(DSL.lower(pp.DISPLAY_NAME).like(prefix, '\\'))
        } else {
            DSL.noCondition()
        }
        val statusCondition = if (!status.isNullOrBlank()) ua.STATUS.eq(status) else DSL.noCondition()
        val cursorCondition = if (cursor != null) {
            val (ts, id) = decodeCursor(cursor)
            // DESC order: the next page holds rows strictly "older" than the cursor.
            DSL.row(ua.CREATED_AT, ua.ID).lt(ts, id)
        } else {
            DSL.noCondition()
        }

        val rows = dsl.select(ua.ID, ua.EMAIL, pp.DISPLAY_NAME, ua.REGION, ua.STATUS, ua.CREATED_AT)
            .from(ua)
            .leftJoin(pp).on(pp.USER_ACCOUNT_ID.eq(ua.ID))
            .where(ua.REGION.eq(region).and(searchCondition).and(statusCondition).and(cursorCondition))
            .orderBy(ua.CREATED_AT.desc(), ua.ID.desc())
            .limit(ADMIN_PAGE_SIZE + 1)
            .fetch()

        val hasMore = rows.size > ADMIN_PAGE_SIZE
        val page = if (hasMore) rows.take(ADMIN_PAGE_SIZE) else rows
        val items = page.map {
            UserAdminSummary(
                id = it[ua.ID]!!,
                email = it[ua.EMAIL]!!,
                displayName = it[pp.DISPLAY_NAME],
                region = it[ua.REGION]!!,
                status = it[ua.STATUS]!!,
                createdAt = it[ua.CREATED_AT]!!,
            )
        }
        val nextCursor = if (hasMore) items.last().let { encodeCursor(it.createdAt, it.id) } else null
        return UserAdminPage(items, nextCursor)
    }

    @Transactional(readOnly = true)
    override fun card(userId: UUID, region: String): UserAdminCard? {
        val ua = USER_ACCOUNT
        val account = dsl.select(ua.EMAIL, ua.REGION, ua.STATUS, ua.CREATED_AT, ua.DELETED_AT)
            .from(ua)
            .where(ua.ID.eq(userId).and(ua.REGION.eq(region)))
            .fetchOne()
            ?.let {
                UserAdminAccount(
                    email = it[ua.EMAIL]!!,
                    region = it[ua.REGION]!!,
                    status = it[ua.STATUS]!!,
                    createdAt = it[ua.CREATED_AT]!!,
                    deletedAt = it[ua.DELETED_AT],
                )
            } ?: return null

        val us = USER_SESSION
        // Timestamps only — ip_hash/user_agent_hash are intentionally NOT selected.
        val sessions = dsl.select(us.CREATED_AT, us.EXPIRES_AT, us.REVOKED_AT)
            .from(us)
            .where(us.USER_ACCOUNT_ID.eq(userId))
            .orderBy(us.CREATED_AT.desc(), us.ID.desc())
            .fetch()
            .map {
                UserAdminSession(
                    createdAt = it[us.CREATED_AT]!!,
                    lastSeenAt = null, // No last-seen column in the schema; kept for API stability.
                    expiresAt = it[us.EXPIRES_AT]!!,
                    revokedAt = it[us.REVOKED_AT],
                )
            }
        return UserAdminCard(account, sessions)
    }

    private fun escapeLike(input: String): String =
        input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun encodeCursor(createdAt: OffsetDateTime, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAt|$id".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(cursor: String): Pair<OffsetDateTime, UUID> = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        val idx = decoded.lastIndexOf('|')
        require(idx > 0)
        OffsetDateTime.parse(decoded.substring(0, idx)) to UUID.fromString(decoded.substring(idx + 1))
    }.getOrElse { throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid cursor") }
}
