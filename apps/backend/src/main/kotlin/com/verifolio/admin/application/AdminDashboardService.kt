package com.verifolio.admin.application

import com.verifolio.privacy.DataSubjectRequestAdminView
import org.springframework.stereotype.Service

/**
 * Admin dashboard read model (spec §DSR review queue — dashboard row). This iteration surfaces the
 * DSR pending counts by status for the admin's region; other counters (users/verification/tickets)
 * are deferred and reported as absent/0 by the controller.
 */
@Service
internal class AdminDashboardService(private val dsrAdminView: DataSubjectRequestAdminView) {

    /** DSR counts by status name for [region], and the total of the non-terminal ("pending") ones. */
    fun dsrSummary(region: String): DsrDashboardSummary =
        DsrDashboardSummary(
            byStatus = dsrAdminView.countByStatus(region),
            pendingTotal = dsrAdminView.pendingCount(region),
        )
}

/** Region-scoped DSR dashboard counts: per-status map + total of the non-terminal statuses. */
data class DsrDashboardSummary(
    val byStatus: Map<String, Int>,
    val pendingTotal: Int,
)
