package com.verifolio.privacy.application

import com.fasterxml.jackson.annotation.JsonInclude
import com.verifolio.contacts.ContactExportData
import com.verifolio.documents.DocumentExportData
import com.verifolio.identity.AccountExportData
import com.verifolio.profiles.ProfileExportData
import com.verifolio.requests.RequestExportData
import java.time.OffsetDateTime

/**
 * The serializable JSON model for a DSR EXPORT artifact (GDPR Art. 15/20 access + portability).
 *
 * Metadata + references ONLY — the package NEVER contains reference-letter text, structured
 * answers, or uploaded/rendered document bytes (the subject retains in-app access to those). Null
 * sections are omitted (a recommender subject has no account/profile/contacts/documents).
 *
 * The account-holder ([SubjectType.ACCOUNT_HOLDER]) package carries every section; the recommender
 * ([SubjectType.RECOMMENDER]) package is intentionally thin (surviving request/consent metadata).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportPackage(
    val generatedAt: OffsetDateTime,
    val subjectType: SubjectType,
    val account: AccountExportData? = null,
    val profile: ProfileExportData? = null,
    val contacts: List<ContactExportData>? = null,
    val referenceRequests: List<RequestExportData>? = null,
    val documents: List<DocumentExportData>? = null,
    val consents: List<ConsentExportData>,
    val dataSubjectRequests: List<DataSubjectRequestExportData>,
) {
    enum class SubjectType { ACCOUNT_HOLDER, RECOMMENDER }
}

/** A consent-record row for the subject (privacy-owned; consent is retained even after erasure). */
data class ConsentExportData(
    val consentType: String,
    val status: String,
    val policyTextVersion: String,
    val grantedAt: OffsetDateTime?,
    val declinedAt: OffsetDateTime?,
    val withdrawnAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

/** DSR-history metadata for the subject (type/status/timestamps only — no resolution content). */
data class DataSubjectRequestExportData(
    val type: String,
    val status: String,
    val verifiedAt: OffsetDateTime?,
    val dueAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?,
)
