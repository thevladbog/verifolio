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

    /** View the admin user list + user card (L2+). */
    USER_VIEW,

    /** View the admin audit-log viewer (L2+). */
    AUDIT_VIEW,

    /** Export audit logs as CSV (SUPERADMIN only). */
    AUDIT_EXPORT,

    /** Future: manage admins/roles (SUPERADMIN only). */
    ADMIN_MANAGE,
}

// Fixed role -> permission matrix (spec §RBAC table). L1 read-only (DSR_VIEW only);
// L2 decides+executes and views users+audit; SUPERADMIN everything (incl AUDIT_EXPORT, ADMIN_MANAGE).
private val ROLE_PERMISSIONS: Map<AdminRole, Set<AdminPermission>> = mapOf(
    AdminRole.SUPPORT_L1 to setOf(AdminPermission.DSR_VIEW),
    AdminRole.SUPPORT_L2 to setOf(
        AdminPermission.DSR_VIEW,
        AdminPermission.DSR_DECIDE,
        AdminPermission.DSR_EXECUTE,
        AdminPermission.USER_VIEW,
        AdminPermission.AUDIT_VIEW,
    ),
    AdminRole.SUPERADMIN to AdminPermission.entries.toSet(),
)

/** True iff this role is granted [permission] by the fixed RBAC matrix. */
fun AdminRole.has(permission: AdminPermission): Boolean =
    ROLE_PERMISSIONS.getValue(this).contains(permission)
