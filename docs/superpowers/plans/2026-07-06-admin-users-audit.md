# Admin User Management + Audit-Log Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two read-only, self-audited admin views — user list + user card, and the audit-log viewer — with new RBAC permissions, on the iteration-13 admin foundation.

**Architecture:** admin composes the user card from new/reused read ports on identity/privacy/profiles/documents; the audit viewer reads a new `audit.AuditLogAdminView`. All endpoints gate via `AdminAuthorization` and self-audit. Spec: `docs/superpowers/specs/2026-07-06-admin-users-audit-design.md` (RBAC matrix + card sections there are normative).

**Tech Stack:** existing Kotlin/Spring/jOOQ + Next.js; no new deps; no schema change.

## Global Constraints

- NO Flyway migration (reads over existing tables). NO new Maven deps (CI Maven-Central 403s new deps).
- New permissions `USER_VIEW` + `AUDIT_VIEW` (L2 + SUPERADMIN) and `AUDIT_EXPORT` (SUPERADMIN); L1 keeps DSR_VIEW only. Every endpoint gates server-side → 403.
- Every admin read of user data / audit logs is audited (`ADMIN_USER_LIST_VIEWED`, `ADMIN_USER_DETAIL_VIEWED`, `ADMIN_AUDIT_LOG_VIEWED`), metadata = region/filters/counts/ids only — never emails/content. `ip_hash`/`user_agent_hash` never returned.
- Read-only: NO mutation endpoints (reset-sessions/disable deferred).
- Support-without-content: the card exposes metadata only; no document/letter/file content.
- Region: cell = region; user views filter `user_account.region` defensively; audit is cell-scoped (no region column).
- Module boundaries one-way (admin → identity/privacy/profiles/documents/audit/platform); ModularityTests green.
- OpenAPI refreshed + `npm run gen:api` committed (check:api gate). Watch GitGuardian (no high-entropy test literals). Integration tests extend `testsupport.IntegrationTest`; Testcontainers via podman (DOCKER_HOST + TESTCONTAINERS_RYUK_DISABLED).

---

### Task 1: RBAC permissions

**Files:** Modify `admin/domain/AdminRole.kt` (add USER_VIEW, AUDIT_VIEW, AUDIT_EXPORT to the enum + role map); Test `admin/AdminRoleTest.kt` (or extend an existing role test).

- [ ] **Step 1:** Add the three permissions; map L2 = existing + USER_VIEW + AUDIT_VIEW; SUPERADMIN = all (incl AUDIT_EXPORT); L1 unchanged (DSR_VIEW only).
- [ ] **Step 2:** Unit test the matrix (L1 lacks USER_VIEW/AUDIT_VIEW; L2 has both but not AUDIT_EXPORT; SUPERADMIN has all). Compile. Commit — `feat(backend): admin USER_VIEW/AUDIT_VIEW/AUDIT_EXPORT permissions`

---

### Task 2: identity.UserAdminView + privacy.UserPrivacySummary

**Files:** Create `identity/UserAdminView.kt` (+ impl), `privacy/UserPrivacySummary.kt` (+ impl); Modify `privacy/application/ExportExecutor.kt` (reuse the new consent read); Tests `identity/UserAdminViewTest.kt`, `privacy/UserPrivacySummaryTest.kt`.

**Interfaces (produce):**
- `identity.UserAdminView`: `list(region: String, query: String?, status: String?, cursor: String?): UserAdminPage` (items `UserAdminSummary(id, email, displayName?, region, status, createdAt)` + nextCursor); `card(userId: UUID, region: String): UserAdminCard?` (`account{email,region,status,createdAt,deletedAt}` + `sessions: List<SessionSummary(createdAt,lastSeenAt?,expiresAt,revokedAt?)>`). Keyset cursor = the DSR/contacts pattern. `displayName` joined from person_profile (LEFT JOIN, may be null).
- `privacy.UserPrivacySummary`: `forUser(userId: UUID): UserPrivacyData(consents: List<ConsentSummary(consentType,status,policyTextVersion,grantedAt?,withdrawnAt?,createdAt)>, dsrCountsByStatus: Map<String,Int>)`. Extract the consent-row read currently inline in ExportExecutor into this port and have ExportExecutor call it (DRY).

- [ ] **Step 1:** Implement both ports (owner/region-scoped jOOQ reads). Refactor ExportExecutor to consume `UserPrivacySummary` for its consents section (keep the export JSON shape identical).
- [ ] **Step 2:** Integration tests: list returns seeded users filtered by query/status + cursor paging + region isolation; card returns account+sessions for the user, 404 for a foreign-region user; UserPrivacySummary returns the user's consents + DSR counts; ExportExecutor test still green (unchanged JSON).
- [ ] **Step 3:** `--tests "*UserAdmin*" --tests "*UserPrivacy*" --tests "*Export*" --tests "*Modularity*"` green. Commit — `feat(backend): user admin read ports (identity + privacy summary)`

---

### Task 3: admin user endpoints (list + card) with RBAC + audit

**Files:** Create `admin/application/AdminUserService.kt` (composes the card from identity/privacy/profiles/documents), `admin/api/AdminUserController.kt` + DTOs; Modify `docs/AUDIT_EVENTS.md`, `docs/API_GUIDELINES.md`, `docs/MODULES.md`; Test `admin/AdminUserIntegrationTest.kt`.

- [ ] **Step 1:** `AdminUserController`:
  - `GET /api/v1/admin/users` → require USER_VIEW → `identity.UserAdminView.list(actor.region, query, status, cursor)`; audit ADMIN_USER_LIST_VIEWED (region, resultCount, filters).
  - `GET /api/v1/admin/users/{id}` → require USER_VIEW → `AdminUserService.card(id, actor.region)` (composes: identity card + profiles.ProfileExport + documents.DocumentExport counts + privacy.UserPrivacySummary); 404 foreign region; audit ADMIN_USER_DETAIL_VIEWED (region, userId).
- [ ] **Step 2: Integration tests** — an L1 admin → GET users 403; an L2/SUPERADMIN → list (audited, region-scoped, search/filter works), card composes all sections (account/profile/document counts/consents/DSR counts/sessions) with NO content, foreign-region user → 404, ADMIN_USER_* audited. ModularityTests green (admin → identity/profiles/documents/privacy).
- [ ] **Step 3:** Commit — `feat(backend): admin user list and card (read-only, audited, USER_VIEW)`

---

### Task 4: audit.AuditLogAdminView + admin audit endpoints

**Files:** Create `audit/AuditLogAdminView.kt` (+ impl in audit/infrastructure), `admin/api/AdminAuditController.kt` + DTOs; Modify `docs/AUDIT_EVENTS.md`, `docs/API_GUIDELINES.md`; Test `audit/AuditLogAdminViewTest.kt`, `admin/AdminAuditIntegrationTest.kt`.

**Interfaces (produce):** `audit.AuditLogAdminView.list(filters: AuditFilters(actorType?, action?, entityType?, from?, to?), cursor: String?): AuditPage`; `exportCsv(filters): ByteArray`. Reads `audit_event`; returns `{id, createdAt, actorType, actorId, action, entityType, entityId, metadata}` — NOT ip_hash/ua_hash.

- [ ] **Step 1:** Implement the view (keyset cursor DESC created_at,id; optional filters as WHERE clauses; action prefix match via LIKE-escaped; from/to on created_at). CSV export bounded to 10k rows (note truncation).
- [ ] **Step 2:** `AdminAuditController`:
  - `GET /api/v1/admin/audit-logs` → require AUDIT_VIEW → list; audit ADMIN_AUDIT_LOG_VIEWED (filters + resultCount).
  - `GET /api/v1/admin/audit-logs/export` → require AUDIT_EXPORT (SUPERADMIN) → CSV (Content-Type text/csv, Content-Disposition attachment); audit ADMIN_AUDIT_LOG_VIEWED (export=true, filters, rowCount).
- [ ] **Step 3: Integration tests** — L1 → 403; L2 → list works + filters (by actorType/action/entityType/date range) + keyset paging + ADMIN_AUDIT_LOG_VIEWED audited (and note the audit-of-audit row appears); L2 → export 403 (needs AUDIT_EXPORT); SUPERADMIN → CSV export with the right columns + no ip/ua hashes; metadata returned as-is (IDs only). ModularityTests green.
- [ ] **Step 4:** Commit — `feat(backend): admin audit-log viewer with CSV export (AUDIT_VIEW/AUDIT_EXPORT)`

---

### Task 5: OpenAPI + docs + full suite

- [ ] **Step 1:** `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`; verify the new user/audit endpoints appear. Full `./gradlew test --rerun` green.
- [ ] **Step 2:** IMPLEMENTATION_HISTORY iteration-15 entry (shipped: user list+card, audit viewer, USER_VIEW/AUDIT_VIEW/AUDIT_EXPORT; deferred: user mutations (reset/disable), verification-signal count, integrity chain, admin-account management, RBAC role expansion). Commit — `docs(backend): OpenAPI + docs for admin user/audit views`

---

### Task 6: frontend — user list + card + audit viewer

**Files:** Regenerate `apps/frontend/lib/api/schema.d.ts` (`npm run gen:api`); Modify `lib/admin/permissions.ts` (USER_VIEW/AUDIT_VIEW/AUDIT_EXPORT helpers) + the admin shell nav (role-gated links); Create `app/(admin)/admin/users/page.tsx`, `app/(admin)/admin/users/[id]/page.tsx`, `app/(admin)/admin/audit/page.tsx`, `components/admin/*`; messages en+ru; Tests.

- [ ] **Step 1:** `npm run gen:api`. Add nav links (Users, Audit) shown per permission from `/admin/me`.
- [ ] **Step 2:** Users list (5b): search + status filter, keyset "Load more", rows link to the card. Guard via useAdminSession; error/loading states (reuse AdminError).
- [ ] **Step 3:** User card (11c): header + profile + count cards (documents/DSRs/consents/sessions) + consent history + sessions list + the support-without-content footer. Read-only, no action buttons. 404 state.
- [ ] **Step 4:** Audit viewer (11b): filter bar (actorType/action/entityType/period), keyset "Load more", append-only note, "Export CSV" button (only if AUDIT_EXPORT) that GETs the export endpoint and downloads. Dates via next-intl formatter.
- [ ] **Step 5:** RTL tests: users list renders + filters + role-gated (no nav without USER_VIEW); card renders sections; audit list renders + filters; export button hidden without AUDIT_EXPORT. `npm run lint && npm run check:api && npm run test -- --run && npm run build` green. Commit — `feat(frontend): admin user management and audit-log viewer`

---

### Task 7: E2E + PR

- [ ] **Step 1:** Extend `e2e/admin.spec.ts`: as the bootstrapped admin (SUPERADMIN), navigate to /admin/users → the seeded account holder appears → open the card → assert metadata sections render and NO document content; navigate to /admin/audit → rows render, filter by action, Export CSV downloads.
- [ ] **Step 2:** Local: backend `./gradlew test --rerun`, frontend suite, `npx playwright test` (compose creds + bootstrap-emails per LOCAL_DEVELOPMENT.md).
- [ ] **Step 3:** Push `feature/admin-users-audit`, open PR, babysit checks + bot review loop (gen:api on any OpenAPI change; watch GitGuardian/Maven-403 lessons).

## Self-review notes

- Spec → tasks: RBAC→T1, read ports→T2, user endpoints→T3, audit viewer→T4, OpenAPI/docs→T5, frontend→T6, E2E→T7. Deferred items (mutations, integrity chain, admin mgmt, role expansion) carry no tasks.
- Reuse: `profiles.ProfileExport`/`documents.DocumentExport` (card counts), `AdminAuthorization`, the keyset-cursor pattern, `AdminError`/`useAdminSession` (frontend).
- Interface consistency: `identity.UserAdminView` + `privacy.UserPrivacySummary` (T2) consumed by `AdminUserService` (T3); `audit.AuditLogAdminView` (T4) consumed by the audit controller; permissions (T1) gate T3/T4 endpoints and T6 nav.
- No schema/Maven change; verify OpenApiContractTest + ModularityTests in the full run.
