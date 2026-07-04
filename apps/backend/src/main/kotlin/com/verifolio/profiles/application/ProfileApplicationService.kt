package com.verifolio.profiles.application

import com.verifolio.audit.AuditService
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.platform.ApiException
import com.verifolio.profiles.ProfileService
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

internal data class ProfileRow(
    val profileId: UUID,
    val userAccountId: UUID,
    val displayName: String,
    val legalName: String?,
    val preferredLocale: String,
    val profileVerificationStatus: String,
)

private val ALLOWED_LOCALES = setOf("en", "ru")

@Service
internal class ProfileApplicationService(
    private val dsl: DSLContext,
    private val audit: AuditService,
) : ProfileService {

    /**
     * Returns the profile ID for the given user account.
     * If no profile exists (e.g. the AFTER_COMMIT listener failed during signup)
     * the profile is created on-the-fly (self-heal) and the new id is returned.
     */
    @Transactional
    override fun requireProfileId(userAccountId: UUID, email: String): UUID {
        val existing = dsl.select(PERSON_PROFILE.ID)
            .from(PERSON_PROFILE)
            .where(PERSON_PROFILE.USER_ACCOUNT_ID.eq(userAccountId))
            .fetchOne(PERSON_PROFILE.ID)
        if (existing != null) return existing

        // Self-heal: profile was not created by the AFTER_COMMIT listener
        createIfAbsent(userAccountId, email)
        return dsl.select(PERSON_PROFILE.ID)
            .from(PERSON_PROFILE)
            .where(PERSON_PROFILE.USER_ACCOUNT_ID.eq(userAccountId))
            .fetchOne(PERSON_PROFILE.ID)!!
    }

    @Transactional
    fun createIfAbsent(userAccountId: UUID, email: String) {
        val displayName = email.substringBefore("@")
        val inserted = dsl.insertInto(PERSON_PROFILE)
            .set(PERSON_PROFILE.USER_ACCOUNT_ID, userAccountId)
            .set(PERSON_PROFILE.DISPLAY_NAME, displayName)
            .onConflict(PERSON_PROFILE.USER_ACCOUNT_ID).doNothing()
            .returning()
            .fetchOne()

        if (inserted != null) {
            audit.record(
                actorType = "SYSTEM",
                actorId = userAccountId.toString(),
                action = "PROFILE_CREATED",
                entityType = "USER_PROFILE",
                entityId = inserted.id.toString(),
            )
        }
    }

    /**
     * Returns the profile for the given user account.
     * If no profile exists the profile is created on-the-fly (self-heal) so that
     * an authenticated user can never receive a 404 on GET /api/v1/profile.
     */
    @Transactional
    fun get(userAccountId: UUID, email: String): ProfileRow {
        val row = dsl.selectFrom(PERSON_PROFILE)
            .where(PERSON_PROFILE.USER_ACCOUNT_ID.eq(userAccountId))
            .fetchOne()
        if (row != null) {
            return ProfileRow(
                profileId = row.id!!,
                userAccountId = row.userAccountId!!,
                displayName = row.displayName!!,
                legalName = row.legalName,
                preferredLocale = row.preferredLocale!!,
                profileVerificationStatus = row.profileVerificationStatus!!,
            )
        }

        // Self-heal: profile was not created by the AFTER_COMMIT listener
        createIfAbsent(userAccountId, email)
        val healed = dsl.selectFrom(PERSON_PROFILE)
            .where(PERSON_PROFILE.USER_ACCOUNT_ID.eq(userAccountId))
            .fetchOne()!!
        return ProfileRow(
            profileId = healed.id!!,
            userAccountId = healed.userAccountId!!,
            displayName = healed.displayName!!,
            legalName = healed.legalName,
            preferredLocale = healed.preferredLocale!!,
            profileVerificationStatus = healed.profileVerificationStatus!!,
        )
    }

    @Transactional
    fun update(
        userAccountId: UUID,
        displayName: String,
        legalName: String?,
        preferredLocale: String,
    ): ProfileRow {
        if (preferredLocale !in ALLOWED_LOCALES) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Unsupported locale '$preferredLocale'. Allowed: $ALLOWED_LOCALES",
            )
        }
        val updated = dsl.update(PERSON_PROFILE)
            .set(PERSON_PROFILE.DISPLAY_NAME, displayName)
            .set(PERSON_PROFILE.LEGAL_NAME, legalName)
            .set(PERSON_PROFILE.PREFERRED_LOCALE, preferredLocale)
            .set(PERSON_PROFILE.UPDATED_AT, OffsetDateTime.now())
            .where(PERSON_PROFILE.USER_ACCOUNT_ID.eq(userAccountId))
            .returning()
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Profile not found for user")

        audit.record(
            actorType = "USER",
            actorId = userAccountId.toString(),
            action = "PROFILE_UPDATED",
            entityType = "USER_PROFILE",
            entityId = updated.id.toString(),
        )

        return ProfileRow(
            profileId = updated.id!!,
            userAccountId = updated.userAccountId!!,
            displayName = updated.displayName!!,
            legalName = updated.legalName,
            preferredLocale = updated.preferredLocale!!,
            profileVerificationStatus = updated.profileVerificationStatus!!,
        )
    }
}
