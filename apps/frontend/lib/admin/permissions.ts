/**
 * Frontend mirror of the backend RBAC role→permission map (spec §RBAC): the
 * server is always the authority (endpoints return 403), but the admin UI hides
 * actions a role cannot perform so it never shows a button that would 403.
 *
 *   DSR_VIEW     → all roles (SUPPORT_L1, SUPPORT_L2, SUPERADMIN)
 *   DSR_DECIDE   → SUPPORT_L2, SUPERADMIN  (approve / reject)
 *   DSR_EXECUTE  → SUPPORT_L2, SUPERADMIN  (execute)
 *   USER_VIEW    → SUPPORT_L2, SUPERADMIN  (user list + card)
 *   AUDIT_VIEW   → SUPPORT_L2, SUPERADMIN  (audit-log viewer)
 *   AUDIT_EXPORT → SUPERADMIN only         (audit-log CSV export)
 */
export type AdminRole = "SUPPORT_L1" | "SUPPORT_L2" | "SUPERADMIN";

const DECIDE_ROLES = new Set<string>(["SUPPORT_L2", "SUPERADMIN"]);
const VIEW_ROLES = new Set<string>(["SUPPORT_L2", "SUPERADMIN"]);

/** Can this role approve/reject a DSR? (DSR_DECIDE) */
export function canDecide(role: string | undefined): boolean {
  return role !== undefined && DECIDE_ROLES.has(role);
}

/** Can this role execute a DSR? (DSR_EXECUTE — same roles as decide this iteration) */
export function canExecute(role: string | undefined): boolean {
  return canDecide(role);
}

/** Can this role browse the user list + open user cards? (USER_VIEW) */
export function canViewUsers(role: string | undefined): boolean {
  return role !== undefined && VIEW_ROLES.has(role);
}

/** Can this role read the audit-log viewer? (AUDIT_VIEW) */
export function canViewAudit(role: string | undefined): boolean {
  return role !== undefined && VIEW_ROLES.has(role);
}

/** Can this role export audit logs to CSV? (AUDIT_EXPORT — SUPERADMIN only) */
export function canExportAudit(role: string | undefined): boolean {
  return role === "SUPERADMIN";
}
