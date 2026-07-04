package com.verifolio.contacts.application

import com.verifolio.audit.AuditService
import com.verifolio.contacts.api.ContactListResponse
import com.verifolio.contacts.api.ContactRequest
import com.verifolio.contacts.api.ContactResponse
import com.verifolio.identity.AuthenticatedUser
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
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
internal class ContactService(
    private val dsl: DSLContext,
    private val profileService: ProfileService,
    private val audit: AuditService,
) {

    @Transactional
    fun create(user: AuthenticatedUser, req: ContactRequest): ContactResponse {
        val ownerProfileId = profileService.requireProfileId(user.userId, user.email)

        val rc = RECOMMENDER_CONTACT
        val record = dsl.insertInto(rc)
            .set(rc.OWNER_PROFILE_ID, ownerProfileId)
            .set(rc.NAME, req.name)
            .set(rc.EMAIL, req.email)
            .set(rc.COMPANY_NAME, req.companyName)
            .set(rc.COMPANY_DOMAIN, req.companyDomain)
            .set(rc.TITLE, req.title)
            .set(rc.RELATIONSHIP_TYPE, req.relationshipType.name)
            .returning()
            .fetchOne()!!

        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "CONTACT_CREATED",
            entityType = "RECOMMENDER_CONTACT",
            entityId = record.id.toString(),
            metadata = mapOf("relationshipType" to req.relationshipType.name),
        )

        return record.toResponse()
    }

    @Transactional(readOnly = true)
    fun get(user: AuthenticatedUser, id: UUID): ContactResponse {
        val ownerProfileId = profileService.requireProfileId(user.userId, user.email)
        val rc = RECOMMENDER_CONTACT
        return dsl.selectFrom(rc)
            .where(rc.ID.eq(id).and(rc.OWNER_PROFILE_ID.eq(ownerProfileId)))
            .fetchOne()
            ?.toResponse()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Contact not found")
    }

    @Transactional
    fun update(user: AuthenticatedUser, id: UUID, req: ContactRequest): ContactResponse {
        val ownerProfileId = profileService.requireProfileId(user.userId, user.email)

        val rc = RECOMMENDER_CONTACT
        val updated = dsl.update(rc)
            .set(rc.NAME, req.name)
            .set(rc.EMAIL, req.email)
            .set(rc.COMPANY_NAME, req.companyName)
            .set(rc.COMPANY_DOMAIN, req.companyDomain)
            .set(rc.TITLE, req.title)
            .set(rc.RELATIONSHIP_TYPE, req.relationshipType.name)
            .set(rc.UPDATED_AT, OffsetDateTime.now())
            .where(rc.ID.eq(id).and(rc.OWNER_PROFILE_ID.eq(ownerProfileId)))
            .returning()
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Contact not found")

        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "CONTACT_UPDATED",
            entityType = "RECOMMENDER_CONTACT",
            entityId = id.toString(),
            metadata = mapOf("relationshipType" to req.relationshipType.name),
        )

        return updated.toResponse()
    }

    @Transactional
    fun delete(user: AuthenticatedUser, id: UUID) {
        val ownerProfileId = profileService.requireProfileId(user.userId, user.email)
        val rc = RECOMMENDER_CONTACT
        val deleted = dsl.deleteFrom(rc)
            .where(rc.ID.eq(id).and(rc.OWNER_PROFILE_ID.eq(ownerProfileId)))
            .execute()

        if (deleted == 0) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Contact not found")

        audit.record(
            actorType = "USER",
            actorId = user.userId.toString(),
            action = "CONTACT_DELETED",
            entityType = "RECOMMENDER_CONTACT",
            entityId = id.toString(),
        )
    }

    @Transactional(readOnly = true)
    fun list(user: AuthenticatedUser, cursor: String?): ContactListResponse {
        val ownerProfileId = profileService.requireProfileId(user.userId, user.email)
        val rc = RECOMMENDER_CONTACT

        val cursorCondition = if (cursor != null) {
            val (cursorTs, cursorId) = decodeCursor(cursor)
            DSL.row(rc.CREATED_AT, rc.ID).gt(cursorTs, cursorId)
        } else {
            DSL.noCondition()
        }

        val rows = dsl.selectFrom(rc)
            .where(rc.OWNER_PROFILE_ID.eq(ownerProfileId).and(cursorCondition))
            .orderBy(rc.CREATED_AT.asc(), rc.ID.asc())
            .limit(PAGE_SIZE + 1)
            .fetch()

        val hasMore = rows.size > PAGE_SIZE
        val items = if (hasMore) rows.take(PAGE_SIZE) else rows

        val nextCursor = if (hasMore) {
            val last = items.last()
            encodeCursor(last.createdAt!!, last.id!!)
        } else {
            null
        }

        return ContactListResponse(
            items = items.map { it.toResponse() },
            nextCursor = nextCursor,
        )
    }

    // ---- helpers ----

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

    private fun com.verifolio.jooq.tables.records.RecommenderContactRecord.toResponse() = ContactResponse(
        id = id!!.toString(),
        name = name!!,
        email = email!!,
        companyName = companyName,
        companyDomain = companyDomain,
        title = title,
        relationshipType = relationshipType!!,
        createdAt = createdAt!!.toString(),
        updatedAt = updatedAt?.toString(),
    )
}
