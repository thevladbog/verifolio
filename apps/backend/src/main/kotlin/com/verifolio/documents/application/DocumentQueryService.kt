package com.verifolio.documents.application

import com.verifolio.audit.AuditService
import com.verifolio.documents.api.DocumentDetailResponse
import com.verifolio.documents.api.DocumentListResponse
import com.verifolio.documents.api.DocumentResponse
import com.verifolio.documents.api.DocumentVersionResponse
import com.verifolio.documents.api.DownloadLinkResponse
import com.verifolio.files.FileStore
import com.verifolio.identity.AuthenticatedUser
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import com.verifolio.platform.ApiException
import com.verifolio.profiles.ProfileService
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
internal class DocumentQueryService(
    private val dsl: DSLContext,
    private val profileService: ProfileService,
    private val fileStore: FileStore,
    private val audit: AuditService,
) {

    @Transactional(readOnly = true)
    fun list(user: AuthenticatedUser, cursor: String?): DocumentListResponse {
        val ownerProfileId = profileService.requireProfileId(user.userId, user.email)
        val d = DOCUMENT

        val cursorCondition = if (cursor != null) {
            val (cursorTs, cursorId) = decodeCursor(cursor)
            DSL.row(d.CREATED_AT, d.ID).gt(cursorTs, cursorId)
        } else {
            DSL.noCondition()
        }

        val rows = dsl.selectFrom(d)
            .where(d.OWNER_PROFILE_ID.eq(ownerProfileId).and(cursorCondition))
            .orderBy(d.CREATED_AT.asc(), d.ID.asc())
            .limit(PAGE_SIZE + 1)
            .fetch()

        val hasMore = rows.size > PAGE_SIZE
        val items = if (hasMore) rows.take(PAGE_SIZE) else rows
        val versionNumbers = currentVersionNumbers(items.mapNotNull { it.currentVersionId })

        return DocumentListResponse(
            items = items.map {
                DocumentResponse(
                    id = it.id!!.toString(),
                    requestId = it.requestId?.toString(),
                    type = it.type!!,
                    status = it.status!!,
                    currentVersionNumber = it.currentVersionId?.let(versionNumbers::get),
                    createdAt = it.createdAt!!.toString(),
                    updatedAt = it.updatedAt?.toString(),
                )
            },
            nextCursor = if (hasMore) encodeCursor(items.last().createdAt!!, items.last().id!!) else null,
        )
    }

    @Transactional(readOnly = true)
    fun get(user: AuthenticatedUser, id: UUID): DocumentDetailResponse {
        val record = loadOwned(user, id)
        val dv = DOCUMENT_VERSION
        val versions = dsl.selectFrom(dv)
            .where(dv.DOCUMENT_ID.eq(id))
            .orderBy(dv.VERSION_NUMBER.asc())
            .fetch()

        return DocumentDetailResponse(
            id = record.id!!.toString(),
            requestId = record.requestId?.toString(),
            type = record.type!!,
            status = record.status!!,
            currentVersionNumber = versions.firstOrNull { it.id == record.currentVersionId }?.versionNumber,
            versions = versions.map {
                DocumentVersionResponse(
                    versionNumber = it.versionNumber!!,
                    status = it.status!!,
                    sha256Hash = it.sha256Hash!!,
                    lockedAt = it.lockedAt!!.toString(),
                    createdAt = it.createdAt!!.toString(),
                )
            },
            createdAt = record.createdAt!!.toString(),
            updatedAt = record.updatedAt?.toString(),
        )
    }

    @Transactional
    fun downloadUrl(user: AuthenticatedUser, id: UUID, versionNumber: Int): DownloadLinkResponse {
        loadOwned(user, id)
        val dv = DOCUMENT_VERSION
        val version = dsl.selectFrom(dv)
            .where(dv.DOCUMENT_ID.eq(id).and(dv.VERSION_NUMBER.eq(versionNumber)))
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Document version not found")
        val pdfFileId = version.pdfFileId
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Document version has no file")

        val link = fileStore.presignedDownloadUrl(pdfFileId)

        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "FILE_DOWNLOAD_GRANTED",
            entityType = "FILE_OBJECT",
            entityId = pdfFileId.toString(),
            metadata = mapOf(
                "documentId" to id.toString(),
                "versionNumber" to versionNumber.toString(),
                "purpose" to "GENERATED_PDF",
            ),
        )
        return DownloadLinkResponse(url = link.url, expiresAt = link.expiresAt.toString())
    }

    // ---- helpers ----

    private fun loadOwned(user: AuthenticatedUser, id: UUID): com.verifolio.jooq.tables.records.DocumentRecord {
        val ownerProfileId = profileService.requireProfileId(user.userId, user.email)
        return dsl.selectFrom(DOCUMENT)
            .where(DOCUMENT.ID.eq(id).and(DOCUMENT.OWNER_PROFILE_ID.eq(ownerProfileId)))
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Document not found")
    }

    private fun currentVersionNumbers(versionIds: List<UUID>): Map<UUID, Int> {
        if (versionIds.isEmpty()) return emptyMap()
        val dv = DOCUMENT_VERSION
        return dsl.select(dv.ID, dv.VERSION_NUMBER).from(dv)
            .where(dv.ID.`in`(versionIds))
            .fetchMap(dv.ID, dv.VERSION_NUMBER)
            .mapNotNull { (k, v) -> if (k != null && v != null) k to v else null }
            .toMap()
    }

    private fun encodeCursor(createdAt: OffsetDateTime, id: UUID): String {
        val raw = "${createdAt}|${id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    private fun decodeCursor(cursor: String): Pair<OffsetDateTime, UUID> {
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val delimIndex = decoded.lastIndexOf('|')
            require(delimIndex > 0)
            OffsetDateTime.parse(decoded.substring(0, delimIndex)) to UUID.fromString(decoded.substring(delimIndex + 1))
        }.getOrElse {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid cursor")
        }
    }
}
