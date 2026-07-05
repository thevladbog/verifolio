package com.verifolio.privacy

import com.verifolio.jooq.tables.records.DataSubjectRequestRecord
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.jooq.tables.references.USER_SESSION
import com.verifolio.platform.ApiException
import com.verifolio.platform.VerifolioProperties
import com.verifolio.privacy.application.AccountDeletionExecutor
import com.verifolio.privacy.application.DataSubjectRequestService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DSR account-holder DELETION executor (GDPR Art. 17): documents tombstoned, profile + owned
 * contacts anonymized, account tombstoned + credentials dropped, consent RETAINED, audit actor
 * pseudonymized, ACCOUNT_DELETED audited, DSR EXECUTED. Plus idempotency + the recommender-scoped
 * DELETION regression.
 */
class AccountDeletionIntegrationTest : PrivacyFlowSupport() {

    @Autowired internal lateinit var service: DataSubjectRequestService
    @Autowired internal lateinit var accountDeletionExecutor: AccountDeletionExecutor
    @Autowired lateinit var props: VerifolioProperties

    private fun userIdFor(email: String): UUID =
        dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT).where(USER_ACCOUNT.EMAIL.eq(email))
            .fetchOne(USER_ACCOUNT.ID)!!

    private fun fetchDsr(dsrId: UUID): DataSubjectRequestRecord =
        dsl.selectFrom(DATA_SUBJECT_REQUEST).where(DATA_SUBJECT_REQUEST.ID.eq(dsrId)).fetchOne()!!

    @Test
    fun `account-holder DELETION erases the subject, retains consent + audit, and executes`() {
        val owner = "del_owner@example.com"
        val recommender = "del_rec@corp.example.com"
        // Seeds: a contact, a reference request, a locked document version, consent records, and
        // (via the owner login + DSR intake below) audit rows with the owner's user id as actor.
        val completed = driveToCompleted(owner, recommender)

        val userId = userIdFor(owner)
        val profileId = dsl.select(PERSON_PROFILE.ID).from(PERSON_PROFILE)
            .where(PERSON_PROFILE.USER_ACCOUNT_ID.eq(userId)).fetchOne(PERSON_PROFILE.ID)!!
        val ownerVersionId = dsl.select(DOCUMENT_VERSION.ID).from(DOCUMENT_VERSION)
            .where(
                DOCUMENT_VERSION.DOCUMENT_ID.`in`(
                    dsl.select(DOCUMENT.ID).from(DOCUMENT).where(DOCUMENT.OWNER_PROFILE_ID.eq(profileId)),
                ),
            ).fetchOne(DOCUMENT_VERSION.ID)!!
        val ownerSha = dsl.select(DOCUMENT_VERSION.SHA256_HASH).from(DOCUMENT_VERSION)
            .where(DOCUMENT_VERSION.ID.eq(ownerVersionId)).fetchOne(DOCUMENT_VERSION.SHA256_HASH)!!

        // Preconditions: a consent row for the account holder, audit rows with the owner as actor.
        val consentsBefore = dsl.fetchCount(CONSENT_RECORD, CONSENT_RECORD.USER_ID.eq(userId))
        assertThat(consentsBefore).isGreaterThan(0)
        assertThat(dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTOR_ID.eq(userId.toString())))
            .isGreaterThan(0)
        assertThat(dsl.fetchCount(USER_SESSION, USER_SESSION.USER_ACCOUNT_ID.eq(userId)))
            .isGreaterThan(0)

        // Owner submits a DELETION DSR (user-scoped, RECEIVED, verified at creation).
        val ownerCookie = login(owner)
        val ownerXsrf = xsrf(ownerCookie)
        val submit = rest.exchange(
            "/api/v1/privacy/data-subject-requests", HttpMethod.POST,
            HttpEntity(mapOf("type" to "DELETION"), authHeaders(ownerCookie, ownerXsrf)),
            Map::class.java,
        )
        assertThat(submit.statusCode).isEqualTo(HttpStatus.CREATED)
        val dsrId = UUID.fromString(submit.body!!["id"] as String)
        val totalAuditBefore = dsl.fetchCount(AUDIT_EVENT)

        // Admin executes.
        val adminActorId = UUID.randomUUID().toString()
        service.execute(dsrId, adminActorId)

        // Document version TOMBSTONED — content nulled, sha256 retained.
        val version = dsl.selectFrom(DOCUMENT_VERSION).where(DOCUMENT_VERSION.ID.eq(ownerVersionId)).fetchOne()!!
        assertThat(version.status).isEqualTo("TOMBSTONED")
        assertThat(version.contentJson).isNull()
        assertThat(version.renderedHtml).isNull()
        assertThat(version.sha256Hash).isEqualTo(ownerSha)

        // Profile anonymized (row retained).
        assertThat(
            dsl.select(PERSON_PROFILE.DISPLAY_NAME).from(PERSON_PROFILE)
                .where(PERSON_PROFILE.ID.eq(profileId)).fetchOne(PERSON_PROFILE.DISPLAY_NAME),
        ).isEqualTo("Deleted user")

        // Owned recommender contact anonymized.
        val contact = dsl.selectFrom(RECOMMENDER_CONTACT)
            .where(RECOMMENDER_CONTACT.ID.eq(completed.contactId)).fetchOne()!!
        assertThat(contact.name).isEqualTo("Deleted contact")
        assertThat(contact.email).isEqualTo("")

        // Account tombstoned: DELETED + deleted_at + email anonymized; sessions + magic links gone.
        val account = dsl.selectFrom(USER_ACCOUNT).where(USER_ACCOUNT.ID.eq(userId)).fetchOne()!!
        assertThat(account.status).isEqualTo("DELETED")
        assertThat(account.deletedAt).isNotNull
        assertThat(account.email).isEqualTo("deleted-$userId@tombstone.invalid")
        assertThat(dsl.fetchCount(USER_SESSION, USER_SESSION.USER_ACCOUNT_ID.eq(userId))).isZero
        assertThat(dsl.fetchCount(MAGIC_LINK_TOKEN, MAGIC_LINK_TOKEN.EMAIL.eq(owner))).isZero

        // Consent RETAINED (lawful-basis evidence).
        assertThat(dsl.fetchCount(CONSENT_RECORD, CONSENT_RECORD.USER_ID.eq(userId)))
            .isEqualTo(consentsBefore)

        // Audit actor pseudonymized: rows retained (total only grew), none still reference the user.
        assertThat(dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTOR_ID.eq(userId.toString()))).isZero
        assertThat(dsl.fetchCount(AUDIT_EVENT)).isGreaterThan(totalAuditBefore)

        // ACCOUNT_DELETED audited (ADMIN actor, counts, no email).
        val deleted = dsl.selectFrom(AUDIT_EVENT)
            .where(
                AUDIT_EVENT.ACTION.eq("ACCOUNT_DELETED")
                    .and(AUDIT_EVENT.ENTITY_ID.eq(userId.toString())),
            ).fetchOne()!!
        assertThat(deleted.actorType).isEqualTo("ADMIN")
        assertThat(deleted.actorId).isEqualTo(adminActorId)
        assertThat(deleted.entityType).isEqualTo("USER_ACCOUNT")
        val meta = deleted.metadata.toString()
        assertThat(meta).contains("versionsTombstoned")
        assertThat(meta).contains("contactsErased")
        assertThat(meta).contains("auditRowsPseudonymized")
        assertThat(meta).contains(dsrId.toString())
        assertThat(meta).doesNotContain(owner)

        // DSR EXECUTED.
        assertThat(
            dsl.select(DATA_SUBJECT_REQUEST.STATUS).from(DATA_SUBJECT_REQUEST)
                .where(DATA_SUBJECT_REQUEST.ID.eq(dsrId)).fetchOne(DATA_SUBJECT_REQUEST.STATUS),
        ).isEqualTo("EXECUTED")

        // Idempotency — the DSR is now EXECUTED (terminal), so a second service.execute is blocked
        // by the transition matrix. Idempotency is therefore verified at the executor level.
        assertThatThrownBy { service.execute(dsrId, adminActorId) }
            .isInstanceOf(ApiException::class.java)
            .satisfies({ ex ->
                ex as ApiException
                assertThat(ex.status).isEqualTo(HttpStatus.CONFLICT)
                assertThat(ex.code).isEqualTo("INVALID_REQUEST_STATE")
            })

        val deletedAtBefore = account.deletedAt
        // Re-run the executor directly: every underlying port no-ops → no error, state unchanged.
        accountDeletionExecutor.execute(fetchDsr(dsrId), adminActorId)
        val afterReRun = dsl.selectFrom(USER_ACCOUNT).where(USER_ACCOUNT.ID.eq(userId)).fetchOne()!!
        assertThat(afterReRun.status).isEqualTo("DELETED")
        assertThat(afterReRun.deletedAt).isEqualTo(deletedAtBefore)
        assertThat(afterReRun.email).isEqualTo("deleted-$userId@tombstone.invalid")
        // The re-run's ACCOUNT_DELETED audit reports zero newly-tombstoned versions / pseudonymized rows.
        val reRun = dsl.selectFrom(AUDIT_EVENT)
            .where(
                AUDIT_EVENT.ACTION.eq("ACCOUNT_DELETED")
                    .and(AUDIT_EVENT.ENTITY_ID.eq(userId.toString())),
            ).orderBy(AUDIT_EVENT.CREATED_AT.desc()).fetch()
        assertThat(reRun.size).isEqualTo(2)
        assertThat(reRun.first().metadata.toString()).contains("\"versionsTombstoned\":\"0\"")
        assertThat(reRun.first().metadata.toString()).contains("\"auditRowsPseudonymized\":\"0\"")
    }

    @Test
    fun `recommender-scoped DELETION still tombstones versions and erases recommender PII`() {
        val owner = "recdel_owner@example.com"
        val recommender = "recdel_rec@corp.example.com"
        val completed = driveToCompleted(owner, recommender)
        val requestId = completed.requestId

        val versionIds = dsl.select(DOCUMENT_VERSION.ID).from(DOCUMENT_VERSION)
            .where(
                DOCUMENT_VERSION.DOCUMENT_ID.`in`(
                    dsl.select(DOCUMENT.ID).from(DOCUMENT).where(DOCUMENT.REQUEST_ID.eq(requestId)),
                ),
            ).fetch(DOCUMENT_VERSION.ID).filterNotNull()
        assertThat(versionIds).isNotEmpty()

        // A verified recommender-scoped DELETION DSR (mirrors the account-less channel post-verify).
        val dsrId = UUID.randomUUID()
        val d = DATA_SUBJECT_REQUEST
        val now = OffsetDateTime.now()
        dsl.insertInto(d)
            .set(d.ID, dsrId)
            .set(d.TYPE, "DELETION")
            .set(d.STATUS, "RECEIVED")
            .set(d.REGION, props.region)
            .set(d.SUBJECT_EMAIL, recommender)
            .set(d.RECOMMENDER_CONTACT_ID, completed.contactId)
            .set(d.REFERENCE_REQUEST_ID, requestId)
            .set(d.VERIFIED_AT, now)
            .set(d.DUE_AT, now.plus(props.privacy.sla))
            .execute()

        service.execute(dsrId)

        // Versions tombstoned (deletion path), recommender PII erased on the request.
        assertThat(
            dsl.fetchCount(
                DOCUMENT_VERSION,
                DOCUMENT_VERSION.ID.`in`(versionIds).and(DOCUMENT_VERSION.STATUS.eq("TOMBSTONED")),
            ),
        ).isEqualTo(versionIds.size)
        val request = dsl.selectFrom(REFERENCE_REQUEST).where(REFERENCE_REQUEST.ID.eq(requestId)).fetchOne()!!
        assertThat(request.recommenderPiiErasedAt).isNotNull()
        assertThat(request.recommenderEmail).isNull()
        assertThat(
            dsl.select(d.STATUS).from(d).where(d.ID.eq(dsrId)).fetchOne(d.STATUS),
        ).isEqualTo("EXECUTED")
    }
}
