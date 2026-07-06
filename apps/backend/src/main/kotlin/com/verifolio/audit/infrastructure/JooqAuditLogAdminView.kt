package com.verifolio.audit.infrastructure

import com.verifolio.audit.AuditFilters
import com.verifolio.audit.AuditLogAdminView
import com.verifolio.audit.AuditLogRow
import com.verifolio.audit.AuditPage
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.platform.ApiException
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

private const val ADMIN_PAGE_SIZE = 50
private const val EXPORT_MAX_ROWS = 10_000

/**
 * Audit-owned implementation of the admin audit-log read model. Newest-first keyset pagination
 * (created_at, id) DESC, optional filters as WHERE clauses. Metadata is parsed from jsonb (reusing
 * the module ObjectMapper); `ip_hash`/`user_agent_hash` are never selected.
 */
@Service
internal class JooqAuditLogAdminView(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : AuditLogAdminView {

    @Transactional(readOnly = true)
    override fun list(filters: AuditFilters, cursor: String?): AuditPage {
        val ae = AUDIT_EVENT
        val cursorCondition = if (cursor != null) {
            val (ts, id) = decodeCursor(cursor)
            // DESC order: the next page holds rows strictly "older" than the cursor.
            DSL.row(ae.CREATED_AT, ae.ID).lt(ts, id)
        } else {
            DSL.noCondition()
        }

        // ip_hash / user_agent_hash are intentionally NOT selected.
        val rows = dsl.select(
            ae.ID, ae.CREATED_AT, ae.ACTOR_TYPE, ae.ACTOR_ID, ae.ACTION,
            ae.ENTITY_TYPE, ae.ENTITY_ID, ae.METADATA,
        )
            .from(ae)
            .where(filterCondition(filters).and(cursorCondition))
            .orderBy(ae.CREATED_AT.desc(), ae.ID.desc())
            .limit(ADMIN_PAGE_SIZE + 1)
            .fetch()

        val hasMore = rows.size > ADMIN_PAGE_SIZE
        val page = if (hasMore) rows.take(ADMIN_PAGE_SIZE) else rows
        val items = page.map {
            AuditLogRow(
                id = it[ae.ID]!!,
                createdAt = it[ae.CREATED_AT]!!,
                actorType = it[ae.ACTOR_TYPE]!!,
                actorId = it[ae.ACTOR_ID],
                action = it[ae.ACTION]!!,
                entityType = it[ae.ENTITY_TYPE],
                entityId = it[ae.ENTITY_ID],
                metadata = parseMetadata(it[ae.METADATA]),
            )
        }
        val nextCursor = if (hasMore) items.last().let { encodeCursor(it.createdAt, it.id) } else null
        return AuditPage(items, nextCursor)
    }

    @Transactional(readOnly = true)
    override fun exportCsv(filters: AuditFilters): ByteArray {
        val ae = AUDIT_EVENT
        // Metadata/hashes are intentionally excluded from the CSV.
        val rows = dsl.select(
            ae.CREATED_AT, ae.ACTOR_TYPE, ae.ACTOR_ID, ae.ACTION, ae.ENTITY_TYPE, ae.ENTITY_ID,
        )
            .from(ae)
            .where(filterCondition(filters))
            .orderBy(ae.CREATED_AT.desc(), ae.ID.desc())
            .limit(EXPORT_MAX_ROWS)
            .fetch()

        val sb = StringBuilder()
        sb.append("createdAt,actorType,actorId,action,entityType,entityId\n")
        for (r in rows) {
            sb.append(csvRow(
                r[ae.CREATED_AT]?.toString(),
                r[ae.ACTOR_TYPE],
                r[ae.ACTOR_ID],
                r[ae.ACTION],
                r[ae.ENTITY_TYPE],
                r[ae.ENTITY_ID],
            ))
            sb.append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Optional filters as an AND-composed condition; actorType/entityType exact, action prefix. */
    private fun filterCondition(filters: AuditFilters): Condition {
        val ae = AUDIT_EVENT
        var cond: Condition = DSL.noCondition()
        if (!filters.actorType.isNullOrBlank()) cond = cond.and(ae.ACTOR_TYPE.eq(filters.actorType))
        if (!filters.action.isNullOrBlank()) {
            // Prefix match, case-insensitive; escape LIKE wildcards in the user-supplied prefix.
            val prefix = escapeLike(filters.action.trim()) + "%"
            cond = cond.and(ae.ACTION.likeIgnoreCase(prefix, '\\'))
        }
        if (!filters.entityType.isNullOrBlank()) cond = cond.and(ae.ENTITY_TYPE.eq(filters.entityType))
        if (filters.from != null) cond = cond.and(ae.CREATED_AT.ge(filters.from))
        if (filters.to != null) cond = cond.and(ae.CREATED_AT.le(filters.to))
        return cond
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMetadata(json: JSONB?): Map<String, Any?> {
        val raw = json?.data() ?: return emptyMap()
        return runCatching { objectMapper.readValue(raw, Map::class.java) as Map<String, Any?> }
            .getOrDefault(emptyMap())
    }

    /** RFC-4180-ish CSV row: quote any field containing a comma, quote, CR or LF; double inner quotes. */
    private fun csvRow(vararg fields: String?): String =
        fields.joinToString(",") { escapeCsv(it) }

    private fun escapeCsv(value: String?): String {
        val v = value ?: ""
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
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
