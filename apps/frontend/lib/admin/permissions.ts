/**
 * Frontend mirror of the backend RBAC role→permission map (spec §RBAC): the
 * server is always the authority (endpoints return 403), but the admin UI hides
 * actions a role cannot perform so it never shows a button that would 403.
 *
 *   DSR_VIEW    → all roles (SUPPORT_L1, SUPPORT_L2, SUPERADMIN)
 *   DSR_DECIDE  → SUPPORT_L2, SUPERADMIN  (approve / reject)
 *   DSR_EXECUTE → SUPPORT_L2, SUPERADMIN  (execute)
 */
export type AdminRole = "SUPPORT_L1" | "SUPPORT_L2" | "SUPERADMIN";

const DECIDE_ROLES = new Set<string>(["SUPPORT_L2", "SUPERADMIN"]);

/** Can this role approve/reject a DSR? (DSR_DECIDE) */
export function canDecide(role: string | undefined): boolean {
  return role !== undefined && DECIDE_ROLES.has(role);
}

/** Can this role execute a DSR? (DSR_EXECUTE — same roles as decide this iteration) */
export function canExecute(role: string | undefined): boolean {
  return canDecide(role);
}
