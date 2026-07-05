package com.verifolio.admin.domain

/**
 * Admin RBAC — three fixed roles with a code-defined permission map (spec §RBAC).
 * Three fixed roles do not warrant DB-driven RBAC; a code map is versioned and reviewable.
 */
enum class AdminRole {
    SUPPORT_L1,
    SUPPORT_L2,
    SUPERADMIN,
}

enum class AdminPermission {
    /** List/detail the DSR queue. */
    DSR_VIEW,

    /** Approve/reject a DSR. */
    DSR_DECIDE,

    /** Run DSR execution. */
    DSR_EXECUTE,

    /** Future: manage admins/roles (SUPERADMIN only). */
    ADMIN_MANAGE,
}

// Fixed role -> permission matrix (spec §RBAC table). L1 read-only; L2 decides+executes;
// SUPERADMIN everything (including ADMIN_MANAGE).
private val ROLE_PERMISSIONS: Map<AdminRole, Set<AdminPermission>> = mapOf(
    AdminRole.SUPPORT_L1 to setOf(AdminPermission.DSR_VIEW),
    AdminRole.SUPPORT_L2 to setOf(
        AdminPermission.DSR_VIEW,
        AdminPermission.DSR_DECIDE,
        AdminPermission.DSR_EXECUTE,
    ),
    AdminRole.SUPERADMIN to AdminPermission.entries.toSet(),
)

/** True iff this role is granted [permission] by the fixed RBAC matrix. */
fun AdminRole.has(permission: AdminPermission): Boolean =
    ROLE_PERMISSIONS.getValue(this).contains(permission)
