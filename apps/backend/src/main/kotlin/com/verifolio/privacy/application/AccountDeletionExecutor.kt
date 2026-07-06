package com.verifolio.privacy.application

import com.verifolio.audit.AuditPseudonymizer
import com.verifolio.audit.AuditService
import com.verifolio.contacts.ContactErasure
import com.verifolio.documents.OwnerErasure
import com.verifolio.identity.AccountErasure
import com.verifolio.jooq.tables.records.DataSubjectRequestRecord
import com.verifolio.profiles.ProfileErasure
import com.verifolio.profiles.ProfileService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Account-holder DELETION executor (GDPR Art. 17). Erases the subject's data across the owning
 * modules per the account-deletion matrix (privacy/DSR design, normative): owned documents are
 * tombstoned (locked-version rules — NULL content, retain sha256/version/lockedAt), profile +
 * contacts PII anonymized, the account itself tombstoned (status DELETED, email anonymized,
 * sessions + magic links dropped), and the subject's audit actor reference pseudonymized. The
 * `consent_record` rows are RETAINED (lawful-basis evidence) and audit rows are never deleted.
 *
 * Runs synchronously inside [DataSubjectRequestService.execute] for
 * `type == DELETION && user_id != null`; the caller owns the RECEIVED/APPROVED → EXECUTED
 * transition and the DATA_SUBJECT_REQUEST_EXECUTED audit.
 *
 * Idempotent: each underlying port is idempotent (an already-DELETED account no-ops,
 * already-tombstoned versions are skipped, an already-anonymized profile/contact re-anonymizes to
 * the same values, and pseudonymization of an already-nulled actor matches nothing), so re-running
 * is safe and produces zero counts.
 */
@Service
internal class AccountDeletionExecutor(
    private val profiles: ProfileService,
    private val ownerErasure: OwnerErasure,
    private val profileErasure: ProfileErasure,
    private val contactErasure: ContactErasure,
    private val accountErasure: AccountErasure,
    private val auditPseudonymizer: AuditPseudonymizer,
    private val audit: AuditService,
) {

    @Transactional
    fun execute(dsr: DataSubjectRequestRecord, adminActorId: String? = null) {
        val userId = dsr.userId!!
        // Resolve the profile BEFORE erasure — profile-scoped erasures (documents, contacts) key
        // off it. The DSR row already carries subject_email (captured at intake), so it survives
        // the account email being anonymized below.
        val profileId = profiles.requireProfileId(userId, dsr.subjectEmail!!)

        // Order per the account-deletion matrix. Pseudonymize the audit actor LAST — after the
        // account is tombstoned — so the acting subject's live processing trail is anonymized only
        // once every erasure it might have driven is recorded.
        val versionsTombstoned = ownerErasure.tombstoneForOwner(profileId).size
        profileErasure.eraseForUser(userId)
        val contactsErased = contactErasure.eraseForOwner(profileId)
        accountErasure.eraseForUser(userId)
        val auditRowsPseudonymized = auditPseudonymizer.pseudonymizeActor(userId.toString())

        // consent_record RETAINED — no action (lawful-basis evidence; FK to the tombstoned account
        // holds). audit_event rows RETAINED — only the actor reference is nulled above.

        // The ACCOUNT_DELETED audit records the acting ADMIN (or SYSTEM), never the deleted user,
        // so it is not itself caught by the actor pseudonymization above. Metadata is ids + counts
        // only — never the subject email or any content.
        audit.record(
            actorType = if (adminActorId != null) "ADMIN" else "SYSTEM",
            actorId = adminActorId,
            action = "ACCOUNT_DELETED",
            entityType = "USER_ACCOUNT",
            entityId = userId.toString(),
            metadata = mapOf(
                "dsrId" to dsr.id.toString(),
                "versionsTombstoned" to versionsTombstoned.toString(),
                "contactsErased" to contactsErased.toString(),
                "auditRowsPseudonymized" to auditRowsPseudonymized.toString(),
            ),
        )
    }
}
