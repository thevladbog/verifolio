package com.verifolio.privacy.api

import com.verifolio.privacy.domain.DsrType
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** Account-holder DSR submission (session). Consent withdrawal for recommenders uses the email channel. */
data class CreateDataSubjectRequestRequest(
    @field:NotNull val type: DsrType?,
    @field:Size(max = 2000) val comment: String? = null,
)

data class DataSubjectRequestResponse(
    val id: String,
    val type: String,
    val status: String,
    val subjectEmail: String,
    val dueAt: String,
    val verifiedAt: String?,
    val resolutionNotes: String?,
    val createdAt: String,
    val updatedAt: String?,
)

data class DataSubjectRequestListResponse(
    val items: List<DataSubjectRequestResponse>,
    val nextCursor: String?,
)

/** Public recommender DSR intake — always answered 202 regardless of match (anti-enumeration). */
data class RecommenderDataRequestRequest(
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String?,
    /** Optional narrowing hint shown in the email; not used for matching in this iteration. */
    @field:Size(max = 320) val referenceRequestEmailHint: String? = null,
)

/** Emailed-code verification: records the requested type and, for CONSENT_WITHDRAWAL, executes it. */
data class RecommenderDsrVerifyRequest(
    @field:NotBlank @field:Pattern(regexp = "\\d{6}") val code: String?,
    @field:NotNull val type: DsrType?,
    /** Optional scope: a single reference request. Absent → all of the subject's requests in-cell. */
    val referenceRequestId: String? = null,
)

data class RecommenderDsrVerifyResponse(
    val status: String,
    /** true when CONSENT_WITHDRAWAL ran to completion; false when the request was only recorded. */
    val executed: Boolean,
    val dueAt: String?,
)
