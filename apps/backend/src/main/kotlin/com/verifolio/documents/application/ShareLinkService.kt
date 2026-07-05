package com.verifolio.documents.application

import com.verifolio.audit.AuditService
import com.verifolio.documents.PinnedPdf
import com.verifolio.documents.ShareLinkAccess
import com.verifolio.documents.SharedVersionView
import com.verifolio.documents.api.CreateShareLinkRequest
import com.verifolio.documents.api.ShareLinkCreatedResponse
import com.verifolio.documents.api.ShareLinkListResponse
import com.verifolio.documents.api.ShareLinkResponse
import com.verifolio.files.FileStore
import com.verifolio.identity.AuthenticatedUser
import com.verifolio.jooq.tables.records.ShareLinkRecord
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import com.verifolio.jooq.tables.references.SHARE_LINK
import com.verifolio.platform.ApiException
import com.verifolio.platform.TokenGenerator
import com.verifolio.platform.TokenHasher
import com.verifolio.platform.VerifolioProperties
import com.verifolio.profiles.ProfileService
import com.verifolio.verification.VerificationSignals
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
internal class ShareLinkService(
    private val dsl: DSLContext,
    private val profileService: ProfileService,
    private val fileStore: FileStore,
    private val verificationSignals: VerificationSignals,
    private val audit: AuditService,
    private val hasher: TokenHasher,
    private val props: VerifolioProperties,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) : ShareLinkAccess {

    @Transactional
    fun create(user: AuthenticatedUser, documentId: UUID, req: CreateShareLinkRequest): ShareLinkCreatedResponse {
        val document = loadOwnedDocument(user, documentId)
        val currentVersionId = document.currentVersionId
            ?: throw ApiException(HttpStatus.CONFLICT, "INVALID_REQUEST_STATE", "Document has no locked version to share")
        val versionNumber = versionNumberOf(currentVersionId)

        val rawToken = TokenGenerator.generate()
        val now = OffsetDateTime.now()
        val expiresAt = req.expiresInDays?.let { now.plusDays(it.toLong()) }

        val sl = SHARE_LINK
        val record = dsl.insertInto(sl)
            .set(sl.DOCUMENT_ID, documentId)
            .set(sl.DOCUMENT_VERSION_ID, currentVersionId)
            .set(sl.TOKEN_HASH, hasher.hash(rawToken))
            .set(sl.VISIBILITY, "PUBLIC")
            .set(sl.EXPIRES_AT, expiresAt)
            .returning()
            .fetchOne()!!

        verificationSignals.createVerified(
            entityType = "SHARE_LINK",
            entityId = record.id!!,
            signalType = "PUBLIC_VERIFICATION_ENABLED",
            evidence = mapOf(
                "documentId" to documentId.toString(),
                "versionNumber" to versionNumber.toString(),
            ),
        )

        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "SHARE_LINK_CREATED",
            entityType = "SHARE_LINK",
            entityId = record.id.toString(),
            metadata = buildMap {
                put("documentId", documentId.toString())
                put("versionNumber", versionNumber.toString())
                expiresAt?.let { put("expiresAt", it.toString()) }
            },
        )

        return ShareLinkCreatedResponse(
            id = record.id.toString(),
            url = "${props.auth.frontendBaseUrl}/verify/$rawToken",
            versionNumber = versionNumber,
            expiresAt = expiresAt?.toString(),
            createdAt = record.createdAt!!.toString(),
        )
    }

    @Transactional(readOnly = true)
    fun list(user: AuthenticatedUser, documentId: UUID): ShareLinkListResponse {
        loadOwnedDocument(user, documentId)
        val sl = SHARE_LINK
        val versionNumbers = versionNumbersForDocument(documentId)
        val items = dsl.selectFrom(sl)
            .where(sl.DOCUMENT_ID.eq(documentId))
            .orderBy(sl.CREATED_AT.desc())
            .fetch()
            .map {
                ShareLinkResponse(
                    id = it.id!!.toString(),
                    versionNumber = versionNumbers[it.documentVersionId] ?: 0,
                    expiresAt = it.expiresAt?.toString(),
                    revokedAt = it.revokedAt?.toString(),
                    createdAt = it.createdAt!!.toString(),
                )
            }
        return ShareLinkListResponse(items)
    }

    @Transactional
    fun revoke(user: AuthenticatedUser, shareLinkId: UUID): ShareLinkResponse {
        val ownerProfileId = profileService.requireProfileId(user.userId, user.email)
        val sl = SHARE_LINK
        val d = DOCUMENT
        val record = dsl.select(sl.asterisk()).from(sl)
            .join(d).on(d.ID.eq(sl.DOCUMENT_ID))
            .where(sl.ID.eq(shareLinkId).and(d.OWNER_PROFILE_ID.eq(ownerProfileId)))
            .fetchOne()
            ?.into(sl)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Share link not found")

        if (record.revokedAt != null) {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_REQUEST_STATE", "Share link is already revoked")
        }

        val updated = dsl.update(sl)
            .set(sl.REVOKED_AT, OffsetDateTime.now())
            .where(sl.ID.eq(shareLinkId).and(sl.REVOKED_AT.isNull))
            .returning()
            .fetchOne()
            ?: throw ApiException(HttpStatus.CONFLICT, "INVALID_REQUEST_STATE", "Share link is already revoked")

        verificationSignals.markRevoked("SHARE_LINK", shareLinkId, "PUBLIC_VERIFICATION_ENABLED")

        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "SHARE_LINK_REVOKED",
            entityType = "SHARE_LINK",
            entityId = shareLinkId.toString(),
            metadata = mapOf("documentId" to record.documentId.toString()),
        )

        return ShareLinkResponse(
            id = updated.id!!.toString(),
            versionNumber = versionNumberOf(updated.documentVersionId!!),
            expiresAt = updated.expiresAt?.toString(),
            revokedAt = updated.revokedAt?.toString(),
            createdAt = updated.createdAt!!.toString(),
        )
    }

    // ---- ShareLinkAccess (public read model) ----

    @Transactional(readOnly = true)
    override fun resolve(rawToken: String): SharedVersionView? {
        val sl = SHARE_LINK
        val link = dsl.selectFrom(sl)
            .where(sl.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .fetchOne()
            ?.takeIf { it.revokedAt == null && (it.expiresAt == null || it.expiresAt!!.isAfter(OffsetDateTime.now())) }
            ?: return null
        return toView(link)
    }

    @Transactional(readOnly = true)
    override fun presignPinnedPdf(rawToken: String): PinnedPdf {
        val view = resolve(rawToken)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Verification page not found")
        val dv = DOCUMENT_VERSION
        val pdfFileId = dsl.select(dv.PDF_FILE_ID).from(dv)
            .where(dv.ID.eq(view.versionId))
            .fetchOne(dv.PDF_FILE_ID)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Verification page not found")
        return PinnedPdf(download = fileStore.presignedDownloadUrl(pdfFileId), fileId = pdfFileId)
    }

    // ---- helpers ----

    private fun toView(link: ShareLinkRecord): SharedVersionView? {
        val d = DOCUMENT
        val dv = DOCUMENT_VERSION
        val doc = dsl.selectFrom(d).where(d.ID.eq(link.documentId)).fetchOne() ?: return null
        val version = dsl.selectFrom(dv).where(dv.ID.eq(link.documentVersionId)).fetchOne() ?: return null
        if (version.status == "TOMBSTONED") return null

        val currentNumber = doc.currentVersionId?.let { versionNumberOf(it) } ?: version.versionNumber!!
        val recipientName = version.contentJson?.data()?.let { json ->
            runCatching { objectMapper.readTree(json).get("recipientName")?.asText() }.getOrNull()
        }

        return SharedVersionView(
            shareLinkId = link.id!!,
            documentId = doc.id!!,
            documentType = doc.type!!,
            ownerProfileId = doc.ownerProfileId!!,
            recipientName = recipientName?.takeIf { it.isNotBlank() && it != "null" },
            requestId = doc.requestId,
            versionId = version.id!!,
            versionNumber = version.versionNumber!!,
            lockedAt = version.lockedAt!!,
            versionStatus = version.status!!,
            supersededByNewerVersion = version.versionNumber!! < currentNumber,
            shareLinkCreatedAt = link.createdAt!!,
        )
    }

    private fun loadOwnedDocument(user: AuthenticatedUser, documentId: UUID): com.verifolio.jooq.tables.records.DocumentRecord {
        val ownerProfileId = profileService.requireProfileId(user.userId, user.email)
        return dsl.selectFrom(DOCUMENT)
            .where(DOCUMENT.ID.eq(documentId).and(DOCUMENT.OWNER_PROFILE_ID.eq(ownerProfileId)))
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Document not found")
    }

    private fun versionNumberOf(versionId: UUID): Int =
        dsl.select(DOCUMENT_VERSION.VERSION_NUMBER).from(DOCUMENT_VERSION)
            .where(DOCUMENT_VERSION.ID.eq(versionId))
            .fetchOne(DOCUMENT_VERSION.VERSION_NUMBER)!!

    private fun versionNumbersForDocument(documentId: UUID): Map<UUID?, Int?> =
        dsl.select(DOCUMENT_VERSION.ID, DOCUMENT_VERSION.VERSION_NUMBER).from(DOCUMENT_VERSION)
            .where(DOCUMENT_VERSION.DOCUMENT_ID.eq(documentId))
            .fetchMap(DOCUMENT_VERSION.ID, DOCUMENT_VERSION.VERSION_NUMBER)
}
