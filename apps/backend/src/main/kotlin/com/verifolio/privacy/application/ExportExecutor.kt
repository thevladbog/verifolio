package com.verifolio.privacy.application

import com.verifolio.audit.AuditService
import com.verifolio.contacts.ContactExport
import com.verifolio.documents.DocumentExport
import com.verifolio.files.FileStore
import com.verifolio.identity.AccountExport
import com.verifolio.jooq.tables.records.DataSubjectRequestRecord
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.notifications.MailPort
import com.verifolio.platform.VerifolioProperties
import com.verifolio.privacy.UserPrivacySummary
import com.verifolio.profiles.ProfileExport
import com.verifolio.profiles.ProfileService
import com.verifolio.requests.RequestExport
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DSR EXPORT executor (GDPR Art. 15/20). Assembles a metadata-and-references JSON package for the
 * subject, stores it as a DATA_EXPORT FileObject, emails the subject a short-lived presigned link,
 * and records the artifact id on the DSR. Metadata only — NO letter text, answers, or file bytes.
 *
 * Runs synchronously inside [DataSubjectRequestService.execute] for `type == EXPORT`; the caller
 * owns the RECEIVED/APPROVED → EXECUTED transition and the DATA_SUBJECT_REQUEST_EXECUTED audit.
 */
@Service
internal class ExportExecutor(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val props: VerifolioProperties,
    private val mail: MailPort,
    private val audit: AuditService,
    private val files: FileStore,
    private val profiles: ProfileService,
    private val accountExport: AccountExport,
    private val profileExport: ProfileExport,
    private val contactExport: ContactExport,
    private val requestExport: RequestExport,
    private val documentExport: DocumentExport,
    private val userPrivacySummary: UserPrivacySummary,
) {

    @Transactional
    fun execute(dsr: DataSubjectRequestRecord, adminActorId: String? = null) {
        // Idempotency guard: an export already produced (its artifact id is recorded on the DSR) is
        // a no-op — never regenerate, re-store, or re-email a second package. Re-fetch the pointer
        // so a stale in-memory record cannot mask an already-run export.
        val exportFileId = dsl.select(DATA_SUBJECT_REQUEST.EXPORT_FILE_ID)
            .from(DATA_SUBJECT_REQUEST)
            .where(DATA_SUBJECT_REQUEST.ID.eq(dsr.id))
            .fetchOne(DATA_SUBJECT_REQUEST.EXPORT_FILE_ID)
        if (exportFileId != null) return

        val pkg = if (dsr.userId != null) assembleAccountHolder(dsr) else assembleRecommender(dsr)

        val bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pkg)
        val stored = files.storeExport(bytes)
        val ttl = props.privacy.exportLinkTtl

        // Record the durable DSR pointer + audit BEFORE the side-effecting email, so a
        // stored/audited artifact is never left without a DSR pointer. The email is LAST: an SMTP
        // failure rolls back this same transaction (pointer + audit + the stored object's rollback
        // compensation), so a retry re-runs cleanly rather than emailing an untracked export.
        val d = DATA_SUBJECT_REQUEST
        dsl.update(d)
            .set(d.EXPORT_FILE_ID, stored.fileId)
            .set(d.UPDATED_AT, OffsetDateTime.now())
            .where(d.ID.eq(dsr.id))
            .execute()

        // Metadata: ids + subjectType only — never the email or any package content.
        audit.record(
            actorType = if (adminActorId != null) "ADMIN" else "SYSTEM",
            actorId = adminActorId,
            action = "DATA_EXPORTED",
            entityType = "DATA_SUBJECT_REQUEST",
            entityId = dsr.id.toString(),
            metadata = mapOf(
                "fileId" to stored.fileId.toString(),
                "subjectType" to pkg.subjectType.name,
            ),
        )

        val link = files.presignedDownloadUrl(stored.fileId, ttl)
        mail.send(
            to = dsr.subjectEmail!!,
            subject = "Your Verifolio data export",
            textBody = buildString {
                appendLine("Your Verifolio data export is ready.")
                appendLine()
                appendLine("Download it here (the link is valid for ${humanizeTtl(ttl)}):")
                appendLine(link.url)
                appendLine()
                appendLine("The package contains metadata and references about your account and activity.")
                appendLine("If you did not request this export, please contact support.")
            },
        )
    }

    private fun assembleAccountHolder(dsr: DataSubjectRequestRecord): ExportPackage {
        val userId = dsr.userId!!
        val profileId = profiles.requireProfileId(userId, dsr.subjectEmail!!)
        return ExportPackage(
            generatedAt = OffsetDateTime.now(),
            subjectType = ExportPackage.SubjectType.ACCOUNT_HOLDER,
            account = accountExport.forUser(userId),
            profile = profileExport.forUser(userId),
            contacts = contactExport.forOwner(profileId),
            referenceRequests = requestExport.forRequester(profileId),
            documents = documentExport.forOwner(profileId),
            consents = userPrivacySummary.forUser(userId).consents.map { it.toConsentExport() },
            dataSubjectRequests = dsrHistoryForUser(userId),
        )
    }

    private fun assembleRecommender(dsr: DataSubjectRequestRecord): ExportPackage {
        val contactId = dsr.recommenderContactId!!
        return ExportPackage(
            generatedAt = OffsetDateTime.now(),
            subjectType = ExportPackage.SubjectType.RECOMMENDER,
            referenceRequests = requestExport.forRecommenderEmail(dsr.subjectEmail!!),
            consents = consentsForRecommender(contactId),
            dataSubjectRequests = dsrHistoryForRecommender(contactId),
        )
    }

    private fun consentsForRecommender(contactId: UUID): List<ConsentExportData> {
        val c = CONSENT_RECORD
        return dsl.selectFrom(c).where(c.RECOMMENDER_CONTACT_ID.eq(contactId)).orderBy(c.CREATED_AT.asc())
            .fetch().map { it.toConsentExport() }
    }

    private fun dsrHistoryForUser(userId: UUID): List<DataSubjectRequestExportData> {
        val d = DATA_SUBJECT_REQUEST
        return dsl.selectFrom(d).where(d.USER_ID.eq(userId)).orderBy(d.CREATED_AT.asc())
            .fetch().map { it.toDsrExport() }
    }

    private fun dsrHistoryForRecommender(contactId: UUID): List<DataSubjectRequestExportData> {
        val d = DATA_SUBJECT_REQUEST
        return dsl.selectFrom(d).where(d.RECOMMENDER_CONTACT_ID.eq(contactId)).orderBy(d.CREATED_AT.asc())
            .fetch().map { it.toDsrExport() }
    }

    private fun com.verifolio.jooq.tables.records.ConsentRecordRecord.toConsentExport() = ConsentExportData(
        consentType = consentType!!,
        status = status!!,
        policyTextVersion = policyTextVersion!!,
        grantedAt = grantedAt,
        declinedAt = declinedAt,
        withdrawnAt = withdrawnAt,
        createdAt = createdAt!!,
    )

    // Account-holder consents come from the shared UserPrivacySummary port (DRY); map its DTO to the
    // export JSON DTO 1:1 so the stored package shape is unchanged.
    private fun com.verifolio.privacy.ConsentSummary.toConsentExport() = ConsentExportData(
        consentType = consentType,
        status = status,
        policyTextVersion = policyTextVersion,
        grantedAt = grantedAt,
        declinedAt = declinedAt,
        withdrawnAt = withdrawnAt,
        createdAt = createdAt,
    )

    private fun DataSubjectRequestRecord.toDsrExport() = DataSubjectRequestExportData(
        type = type!!,
        status = status!!,
        verifiedAt = verifiedAt,
        dueAt = dueAt!!,
        createdAt = createdAt!!,
        updatedAt = updatedAt,
    )

    private fun humanizeTtl(ttl: Duration): String {
        val days = ttl.toDays()
        if (days > 0 && ttl == Duration.ofDays(days)) return if (days == 1L) "1 day" else "$days days"
        val hours = ttl.toHours()
        if (hours > 0 && ttl == Duration.ofHours(hours)) return if (hours == 1L) "1 hour" else "$hours hours"
        val minutes = ttl.toMinutes()
        return if (minutes == 1L) "1 minute" else "$minutes minutes"
    }
}
