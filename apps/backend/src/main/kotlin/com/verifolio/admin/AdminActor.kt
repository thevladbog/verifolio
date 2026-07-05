package com.verifolio.admin

import com.verifolio.admin.domain.AdminRole
import java.util.UUID

/**
 * Admin principal (public admin-module API, like identity's AuthenticatedUser). Resolved from a
 * valid admin session and injected via @AuthenticationPrincipal on admin controllers (Task 3).
 * Region is the admin's cell region and scopes all admin reads.
 */
data class AdminActor(
    val adminId: UUID,
    val email: String,
    val role: AdminRole,
    val region: String,
)
