package com.verifolio.requests.application

import com.verifolio.identity.AuthenticatedUser
import com.verifolio.jooq.tables.references.FILE_OBJECT
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.jooq.tables.references.REFERENCE_RESPONSE
import com.verifolio.jooq.tables.references.RESPONSE_UPLOAD
import com.verifolio.platform.ApiException
import com.verifolio.profiles.ProfileService
import com.verifolio.requests.api.SubmittedResponseView
import com.verifolio.requests.api.UploadMeta
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Owner-scoped read of the latest submitted response so the recipient can review it
 * before accept/request-correction (Flow 4). Read-only; not audited — no new
 * authorization boundary (accept already implies this read), same reasoning as
 * template reads. Available in any status once a submitted response exists.
 */
@Service
internal class ResponseReviewService(
    private val dsl: DSLContext,
    private val profileService: ProfileService,
    private val objectMapper: ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun getSubmittedResponse(user: AuthenticatedUser, id: UUID): SubmittedResponseView {
        val requesterProfileId = profileService.requireProfileId(user.userId, user.email)
        val rr = REFERENCE_REQUEST
        val owned = dsl.fetchExists(
            dsl.selectFrom(rr).where(rr.ID.eq(id).and(rr.REQUESTER_PROFILE_ID.eq(requesterProfileId))),
        )
        if (!owned) {
            throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Reference request not found")
        }

        // Same query as accept, without forUpdate — a plain read.
        val resp = REFERENCE_RESPONSE
        val response = dsl.selectFrom(resp)
            .where(resp.REQUEST_ID.eq(id).and(resp.SUBMITTED_AT.isNotNull))
            .orderBy(resp.SUBMITTED_AT.desc())
            .limit(1)
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No submitted response for this reference request")

        // Metadata only (no storage URLs, no pre-accept downloads); READY uploads mirror
        // exactly what accept would attach to the locked version.
        val ru = RESPONSE_UPLOAD
        val fo = FILE_OBJECT
        val uploads = dsl.select(ru.ID, ru.KIND, ru.SHARED_PUBLICLY, ru.TARGET_UPLOAD_ID, fo.MIME_TYPE, fo.SIZE_BYTES)
            .from(ru)
            .join(fo).on(fo.ID.eq(ru.FILE_OBJECT_ID))
            .where(ru.REQUEST_ID.eq(id).and(fo.STATUS.eq("READY")))
            .orderBy(ru.CREATED_AT.asc())
            .fetch { row ->
                UploadMeta(
                    id = row[ru.ID]!!.toString(),
                    kind = row[ru.KIND]!!,
                    contentType = row[fo.MIME_TYPE]!!,
                    sizeBytes = row[fo.SIZE_BYTES]!!,
                    sharedPublicly = row[ru.SHARED_PUBLICLY]!!,
                    targetUploadId = row[ru.TARGET_UPLOAD_ID]?.toString(),
                )
            }

        return SubmittedResponseView(
            approvedLetterText = response.approvedLetterText,
            answers = parseJson(response.answersJson!!.data()),
            submittedAt = response.submittedAt!!.toString(),
            recipientConfirmed = response.recipientConfirmed!!,
            relationshipConfirmed = response.relationshipConfirmed!!,
            uploads = uploads,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(json: String): Map<String, Any?> =
        objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
}
