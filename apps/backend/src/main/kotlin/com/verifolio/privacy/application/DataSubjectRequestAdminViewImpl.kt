package com.verifolio.privacy.application

import com.verifolio.jooq.tables.records.DataSubjectRequestRecord
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.platform.ApiException
import com.verifolio.privacy.DataSubjectRequestAdminView
import com.verifolio.privacy.DsrAdminDetail
import com.verifolio.privacy.DsrAdminItem
import com.verifolio.privacy.DsrAdminPage
import com.verifolio.privacy.domain.DsrStatus
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
 * Privacy-owned implementation of the admin DSR read model. Newest-first keyset pagination
 * (createdAt, id) DESC, region-scoped, optional status filter. Returns metadata-only DTOs.
 */
@Service
internal class DataSubjectRequestAdminViewImpl(
    private val dsl: DSLContext,
    private val service: DataSubjectRequestService,
) : DataSubjectRequestAdminView {

    @Transactional(readOnly = true)
    override fun listForRegion(region: String, status: String?, cursor: String?): DsrAdminPage {
        val dsr = DATA_SUBJECT_REQUEST
        val statusCondition = if (status != null) {
            // Validate against the enum so an unknown status is a clean 400, not a silent empty page.
            val parsed = runCatching { DsrStatus.valueOf(status) }.getOrElse {
                throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unknown status")
            }
            dsr.STATUS.eq(parsed.name)
        } else {
            DSL.noCondition()
        }
        val cursorCondition = if (cursor != null) {
            val (ts, id) = decodeCursor(cursor)
            // DESC order: the next page holds rows strictly "older" than the cursor.
            DSL.row(dsr.CREATED_AT, dsr.ID).lt(ts, id)
        } else {
            DSL.noCondition()
        }
        val rows = dsl.selectFrom(dsr)
            .where(dsr.REGION.eq(region).and(statusCondition).and(cursorCondition))
            .orderBy(dsr.CREATED_AT.desc(), dsr.ID.desc())
            .limit(ADMIN_PAGE_SIZE + 1)
            .fetch()

        val hasMore = rows.size > ADMIN_PAGE_SIZE
        val items = if (hasMore) rows.take(ADMIN_PAGE_SIZE) else rows
        val nextCursor = if (hasMore) items.last().let { encodeCursor(it.createdAt!!, it.id!!) } else null
        return DsrAdminPage(items.map { it.toItem() }, nextCursor)
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID, region: String): DsrAdminDetail? {
        val dsr = DATA_SUBJECT_REQUEST
        val record = dsl.selectFrom(dsr)
            .where(dsr.ID.eq(id).and(dsr.REGION.eq(region)))
            .fetchOne() ?: return null
        return record.toDetail()
    }

    @Transactional(readOnly = true)
    override fun countByStatus(region: String): Map<String, Int> {
        val dsr = DATA_SUBJECT_REQUEST
        return dsl.select(dsr.STATUS, DSL.count())
            .from(dsr)
            .where(dsr.REGION.eq(region))
            .groupBy(dsr.STATUS)
            .fetch()
            .associate { it.value1()!! to it.value2() }
    }

    @Transactional(readOnly = true)
    override fun pendingCount(region: String): Int {
        val dsr = DATA_SUBJECT_REQUEST
        val pendingStatuses = DsrStatus.entries.filter { !it.terminal }.map { it.name }
        return dsl.fetchCount(
            dsl.selectFrom(dsr).where(dsr.REGION.eq(region).and(dsr.STATUS.`in`(pendingStatuses))),
        )
    }

    @Transactional
    override fun approve(id: UUID, region: String, adminActorId: UUID): DsrAdminDetail {
        requireInRegion(id, region)
        service.approve(id, adminActorId.toString())
        return get(id, region)!!
    }

    @Transactional
    override fun reject(id: UUID, region: String, adminActorId: UUID, notes: String?): DsrAdminDetail {
        requireInRegion(id, region)
        service.reject(id, adminActorId.toString(), notes)
        return get(id, region)!!
    }

    @Transactional
    override fun execute(id: UUID, region: String, adminActorId: UUID): DsrAdminDetail {
        requireInRegion(id, region)
        service.execute(id, adminActorId.toString())
        return get(id, region)!!
    }

    /** Region gate for decisions: a DSR outside [region] is a 404 for this admin (cell scoping). */
    private fun requireInRegion(id: UUID, region: String) {
        val dsr = DATA_SUBJECT_REQUEST
        val inRegion = dsl.fetchExists(dsl.selectFrom(dsr).where(dsr.ID.eq(id).and(dsr.REGION.eq(region))))
        if (!inRegion) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Data request not found")
    }

    private fun DataSubjectRequestRecord.toItem() = DsrAdminItem(
        id = id!!,
        type = type!!,
        status = status!!,
        subjectEmail = subjectEmail!!,
        dueAt = dueAt!!,
        createdAt = createdAt!!,
        resolutionNotes = resolutionNotes,
    )

    private fun DataSubjectRequestRecord.toDetail() = DsrAdminDetail(
        id = id!!,
        type = type!!,
        status = status!!,
        subjectEmail = subjectEmail!!,
        region = region!!,
        dueAt = dueAt!!,
        verifiedAt = verifiedAt,
        resolutionNotes = resolutionNotes,
        createdAt = createdAt!!,
        updatedAt = updatedAt,
    )

    private fun encodeCursor(createdAt: OffsetDateTime, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAt|$id".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(cursor: String): Pair<OffsetDateTime, UUID> = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        val idx = decoded.lastIndexOf('|')
        require(idx > 0)
        OffsetDateTime.parse(decoded.substring(0, idx)) to UUID.fromString(decoded.substring(idx + 1))
    }.getOrElse { throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid cursor") }
}
