package com.verifolio.organizations.application

import com.verifolio.jooq.tables.references.ORGANIZATION
import com.verifolio.jooq.tables.references.ORGANIZATION_DOMAIN
import com.verifolio.organizations.OrganizationLookup
import com.verifolio.organizations.OrganizationView
import com.verifolio.organizations.api.OrganizationListResponse
import com.verifolio.platform.ApiException
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

private const val PAGE_SIZE = 50

@Service
internal class OrganizationQueryService(
    private val dsl: DSLContext,
    private val organizationLookup: OrganizationLookup,
) {

    @Transactional(readOnly = true)
    fun list(query: String?, cursor: String?): OrganizationListResponse {
        val o = ORGANIZATION
        val od = ORGANIZATION_DOMAIN

        val cursorCondition = if (cursor != null) {
            val (cursorTs, cursorId) = decodeCursor(cursor)
            DSL.row(o.CREATED_AT, o.ID).gt(cursorTs, cursorId)
        } else {
            DSL.noCondition()
        }

        val queryCondition = if (!query.isNullOrBlank()) {
            val prefix = escapeLike(query.trim().lowercase()) + "%"
            DSL.lower(o.NAME).like(prefix, '\\')
                .or(
                    DSL.exists(
                        DSL.selectOne().from(od)
                            .where(od.ORGANIZATION_ID.eq(o.ID))
                            .and(DSL.lower(od.DOMAIN).like(prefix, '\\')),
                    ),
                )
        } else {
            DSL.noCondition()
        }

        val rows = dsl.selectFrom(o)
            .where(cursorCondition.and(queryCondition))
            .orderBy(o.CREATED_AT.asc(), o.ID.asc())
            .limit(PAGE_SIZE + 1)
            .fetch()

        val hasMore = rows.size > PAGE_SIZE
        val page = if (hasMore) rows.take(PAGE_SIZE) else rows

        val domainsByOrg = domainsFor(page.map { it.id!! })

        val items = page.map { rec ->
            OrganizationView(
                id = rec.id!!,
                name = rec.name!!,
                domains = domainsByOrg[rec.id!!] ?: emptyList(),
                verificationStatus = rec.verificationStatus!!,
            )
        }

        val nextCursor = if (hasMore) {
            val last = page.last()
            encodeCursor(last.createdAt!!, last.id!!)
        } else {
            null
        }

        return OrganizationListResponse(items = items, nextCursor = nextCursor)
    }

    @Transactional(readOnly = true)
    fun getById(id: UUID): OrganizationView =
        toView(id) ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found")

    @Transactional(readOnly = true)
    fun lookupByDomain(domain: String): OrganizationView {
        val match = organizationLookup.findVerifiedByDomain(domain)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No verified organization for domain")
        return toView(match.organizationId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found")
    }

    // ---- helpers ----

    private fun toView(id: UUID): OrganizationView? {
        val o = ORGANIZATION
        val rec = dsl.selectFrom(o).where(o.ID.eq(id)).fetchOne() ?: return null
        return OrganizationView(
            id = rec.id!!,
            name = rec.name!!,
            domains = domainsFor(listOf(id))[id] ?: emptyList(),
            verificationStatus = rec.verificationStatus!!,
        )
    }

    private fun domainsFor(orgIds: List<UUID>): Map<UUID, List<String>> {
        if (orgIds.isEmpty()) return emptyMap()
        val od = ORGANIZATION_DOMAIN
        return dsl.select(od.ORGANIZATION_ID, od.DOMAIN)
            .from(od)
            .where(od.ORGANIZATION_ID.`in`(orgIds))
            .orderBy(od.DOMAIN.asc())
            .fetch()
            .filter { it[od.ORGANIZATION_ID] != null && it[od.DOMAIN] != null }
            .groupBy({ it[od.ORGANIZATION_ID]!! }, { it[od.DOMAIN]!! })
    }

    private fun escapeLike(input: String): String =
        input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun encodeCursor(createdAt: OffsetDateTime, id: UUID): String {
        val raw = "${createdAt}|${id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    private fun decodeCursor(cursor: String): Pair<OffsetDateTime, UUID> {
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val delimIndex = decoded.lastIndexOf('|')
            require(delimIndex > 0)
            val ts = OffsetDateTime.parse(decoded.substring(0, delimIndex))
            val id = UUID.fromString(decoded.substring(delimIndex + 1))
            ts to id
        }.getOrElse {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid cursor")
        }
    }
}
