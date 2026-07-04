package com.verifolio.profiles.application

import com.verifolio.audit.AuditService
import com.verifolio.jooq.tables.records.PersonProfileRecord
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.platform.ApiException
import com.verifolio.platform.SupportedLocales
import com.verifolio.profiles.ProfileService
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
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

    @Transactional(readOnly = true)
    override fun displayName(profileId: UUID): String? =
        dsl.select(PERSON_PROFILE.DISPLAY_NAME)
            .from(PERSON_PROFILE)
            .where(PERSON_PROFILE.ID.eq(profileId))
            .fetchOne(PERSON_PROFILE.DISPLAY_NAME)

    /**
     * Inserts a profile row for [userAccountId] if one does not already exist.
     *
     * REQUIRES_NEW: this method must always run in its own transaction so that the
     * INSERT is committed immediately on return. When called from an AFTER_COMMIT
     * listener the original transaction's resources are still bound to the thread
     * but no further commit follows — using the default REQUIRED propagation would
     * join those dead resources and silently discard the INSERT. REQUIRES_NEW
     * suspends any outer transaction, opens a fresh one, commits it, then resumes
     * the outer context. This is safe when called from self-heal paths too (the
     * ON CONFLICT DO NOTHING guard prevents duplicate rows).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        if (row != null) return row.toProfileRow()

        // Self-heal: profile was not created by the AFTER_COMMIT listener
        createIfAbsent(userAccountId, email)
        return dsl.selectFrom(PERSON_PROFILE)
            .where(PERSON_PROFILE.USER_ACCOUNT_ID.eq(userAccountId))
            .fetchOne()!!
            .toProfileRow()
    }

    /**
     * Updates the profile for the given user account.
     * Self-heals if the profile row is missing (mirrors GET behaviour) so that an
     * authenticated user can never receive a 404 on PUT /api/v1/profile.
     */
    @Transactional
    fun update(
        userAccountId: UUID,
        email: String,
        displayName: String,
        legalName: String?,
        preferredLocale: String,
    ): ProfileRow {
        if (preferredLocale !in SupportedLocales.ALL) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Unsupported locale '$preferredLocale'. Allowed: ${SupportedLocales.ALL}",
            )
        }
        // Self-heal: ensure the profile row exists before updating
        createIfAbsent(userAccountId, email)

        val updated = dsl.update(PERSON_PROFILE)
            .set(PERSON_PROFILE.DISPLAY_NAME, displayName)
            .set(PERSON_PROFILE.LEGAL_NAME, legalName)
            .set(PERSON_PROFILE.PREFERRED_LOCALE, preferredLocale)
            .set(PERSON_PROFILE.UPDATED_AT, OffsetDateTime.now())
            .where(PERSON_PROFILE.USER_ACCOUNT_ID.eq(userAccountId))
            .returning()
            .fetchOne()
            ?: throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PROFILE_ERROR", "Profile missing after self-heal")

        audit.record(
            actorType = "USER",
            actorId = userAccountId.toString(),
            action = "PROFILE_UPDATED",
            entityType = "USER_PROFILE",
            entityId = updated.id.toString(),
        )

        return updated.toProfileRow()
    }

    // ---- helpers ----

    private fun PersonProfileRecord.toProfileRow() = ProfileRow(
        profileId = id!!,
        userAccountId = userAccountId!!,
        displayName = displayName!!,
        legalName = legalName,
        preferredLocale = preferredLocale!!,
        profileVerificationStatus = profileVerificationStatus!!,
    )
}
