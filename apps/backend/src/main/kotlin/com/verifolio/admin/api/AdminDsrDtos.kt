package com.verifolio.admin.api

import com.verifolio.privacy.DsrAdminDetail
import com.verifolio.privacy.DsrAdminItem
import jakarta.validation.constraints.Size

/** Dashboard summary: DSR counts by status + total pending, for the admin's region. */
data class AdminDashboardResponse(
    val dsrByStatus: Map<String, Int>,
    val dsrPendingTotal: Int,
)

/** One DSR queue row (metadata only — no letter/answer/upload content). */
data class AdminDsrItemResponse(
    val id: String,
    val type: String,
    val status: String,
    val subjectEmail: String,
    val dueAt: String,
    val createdAt: String,
    val resolutionNotes: String?,
) {
    companion object {
        fun from(item: DsrAdminItem) = AdminDsrItemResponse(
            id = item.id.toString(),
            type = item.type,
            status = item.status,
            subjectEmail = item.subjectEmail,
            dueAt = item.dueAt.toString(),
            createdAt = item.createdAt.toString(),
            resolutionNotes = item.resolutionNotes,
        )
    }
}

/** A keyset page of DSR queue rows. */
data class AdminDsrListResponse(
    val items: List<AdminDsrItemResponse>,
    val nextCursor: String?,
)

/** Full DSR detail (still metadata only). */
data class AdminDsrDetailResponse(
    val id: String,
    val type: String,
    val status: String,
    val subjectEmail: String,
    val region: String,
    val dueAt: String,
    val verifiedAt: String?,
    val resolutionNotes: String?,
    val createdAt: String,
    val updatedAt: String?,
) {
    companion object {
        fun from(d: DsrAdminDetail) = AdminDsrDetailResponse(
            id = d.id.toString(),
            type = d.type,
            status = d.status,
            subjectEmail = d.subjectEmail,
            region = d.region,
            dueAt = d.dueAt.toString(),
            verifiedAt = d.verifiedAt?.toString(),
            resolutionNotes = d.resolutionNotes,
            createdAt = d.createdAt.toString(),
            updatedAt = d.updatedAt?.toString(),
        )
    }
}

/** Reject body: optional resolution notes recorded on the DSR and shown to reviewers. */
data class AdminDsrRejectRequest(
    @field:Size(max = 2000) val notes: String? = null,
)

/** Approve/reject/execute result: the DSR's new status. */
data class AdminDsrStatusResponse(val id: String, val status: String)
