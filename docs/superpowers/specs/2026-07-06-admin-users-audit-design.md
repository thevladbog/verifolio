# Admin User Management + Audit-Log Viewer — Design

Date: 2026-07-06
Status: approved (user: scope = user list+card + audit viewer; user card is read-only)
Scope: iteration 15 — two read-only, self-audited admin views on top of the iteration-13
admin foundation: **user list + user card** (design 5b/11c) and the **audit-log viewer**
(design 11b). No mutations (reset-sessions / disable-account are deferred with their
dual-approval design). Admin-account management (invitations, role changes, new roles) is
a separate later iteration.

Normative sources: AUTHENTICATION.md §Admin (every admin read of user data audited;
admins cannot modify locked versions/signals), PRIVACY_AND_DATA_CLASSIFICATION.md
(audit logs readable only by per-region admins; every admin read of audit logs itself
audited; support-without-content), AUDIT_EVENTS.md, design 5b/11b/11c. Reuses the
iteration-13 admin RBAC/session/region-scope and the iteration-14 export read ports.

## No schema change

Both views are reads over existing tables (`user_account`, `user_session`,
`person_profile`, `document`/`document_version`, `consent_record`, `data_subject_request`,
`audit_event`). No Flyway migration this iteration.

## RBAC — two new permissions

Add to `admin.domain.AdminPermission`: `USER_VIEW` and `AUDIT_VIEW`. Matrix (design 11d:
"L2 = view PII"; L1 is first-line support without PII):

| Permission | L1 | L2 | SUPERADMIN |
|---|---|---|---|
| DSR_VIEW / DSR_DECIDE / DSR_EXECUTE | (existing) | (existing) | (existing) |
| `USER_VIEW` (user list + card) | | ✓ | ✓ |
| `AUDIT_VIEW` (audit-log viewer) | | ✓ | ✓ |
| `AUDIT_EXPORT` (CSV export) | | | ✓ |
| ADMIN_MANAGE | | | ✓ |

L1 keeps DSR_VIEW only (tickets/diagnostics are not built yet). Endpoints gate via the
existing `AdminAuthorization.require(actor, permission)` → 403.

## Region scoping — the cell IS the region

`audit_event` has no region column, and that is correct: each cell is a single region with
its own database, so reading this cell's `audit_event`/`user_account` IS reading this
region's data. `user_account.region` is still filtered defensively on the user views (it is
denormalized there). The audit viewer needs no region filter — physical deployment scopes
it. (A future GLOBAL cell would revisit this.)

## User views (identity + composition)

### List — `GET /api/v1/admin/users?query=&status=&cursor=` (USER_VIEW)

`identity.UserAdminView.list(region, query?, status?, cursor)` → keyset page (50, DESC by
created_at,id) of lightweight summaries: `{id, email, displayName?, region, status,
createdAt}`. `query` matches email/displayName prefix (ILIKE, escaped); `status` filters
ACTIVE/DISABLED/DELETED. No per-row counts (avoids N+1; counts live on the card). Audits
`ADMIN_USER_LIST_VIEWED` (metadata: region, resultCount, filters — no emails).

### Card — `GET /api/v1/admin/users/{id}` (USER_VIEW)

An `AdminUserService` (admin module) composes the card from read ports (each owning module
owns its data; admin only reads):

| Card section | Source |
|---|---|
| account: email, region, status, createdAt, deletedAt | `identity.UserAdminView.card(userId, region)` |
| sessions: [createdAt, lastSeenAt, expiresAt, revokedAt] | `identity.UserAdminView.card` (identity owns user_session; NO geo — only opaque ip/ua hashes exist, so device/location is not shown) |
| profile: displayName, legalName, preferredLocale | `profiles.ProfileExport.forUser` (reused) |
| documents: total + locked counts | `documents.DocumentExport.forOwner` (reused; counted in admin) |
| consents: [consentType, status, policyTextVersion, timestamps] | `privacy.UserPrivacySummary.forUser` (new; extracted from the export executor's consent read — DRY) |
| dataSubjectRequests: counts by status | `privacy.UserPrivacySummary.forUser` |

404 if the user is not in the admin's region. Audits `ADMIN_USER_DETAIL_VIEWED`
(metadata: region, userId). **Support-without-content held**: the card exposes only
metadata (email, status, counts, consent/session metadata) — never document/letter/answer
content or files.

Deferred from the card (design shows, not built): verification-signal count (profile
verification is post-MVP — signals are per-document, not per-user), failed-login-30d
counter, active-share-link count, device/location (only hashes exist).

## Audit-log viewer (audit module)

### List — `GET /api/v1/admin/audit-logs?actorType=&action=&entityType=&from=&to=&cursor=` (AUDIT_VIEW)

New `audit.AuditLogAdminView.list(filters, cursor)` → keyset page (50, DESC by created_at,id)
of `{id, createdAt, actorType, actorId, action, entityType, entityId, metadata}`. Filters
(all optional): `actorType` (enum), `action` (exact or prefix), `entityType`, `from`/`to`
(created_at range). `metadata` is already IDs/counts-only by construction (no PII), so it is
returned as-is; `ip_hash`/`user_agent_hash` are NOT returned. Reads the existing
`audit_event` table (no region column — cell-scoped, see above).

### CSV export — `GET /api/v1/admin/audit-logs/export?...` (AUDIT_EXPORT, SUPERADMIN)

Same filters, streams a CSV (`createdAt,actorType,actorId,action,entityType,entityId`),
bounded to a sane max rows (e.g. 10k) with a note if truncated. Audited.

### Self-audit

Every audit-log read (list + export) writes `ADMIN_AUDIT_LOG_VIEWED` (actor ADMIN,
metadata: applied filters + resultCount). This is the "every admin read of audit logs is
itself audited" rule — it does create audit-of-audit rows, which is intended.

Deferred: the design's "integrity chain confirmed ✓" — `audit_event` is append-only by
permissions/convention (no cryptographic hash-chaining column). Chained integrity is a
tracked follow-up; the viewer shows the append-only note without a chain claim.

## New public read ports

- `identity.UserAdminView` — `list(region, query?, status?, cursor): UserAdminPage`;
  `card(userId, region): UserAdminCard?` (account fields + sessions). identity owns
  user_account + user_session.
- `privacy.UserPrivacySummary` — `forUser(userId): UserPrivacyData` (consents list + DSR
  counts by status). Extract the consent read from `ExportExecutor` so both use it.
- `audit.AuditLogAdminView` — `list(filters, cursor): AuditPage`; `exportCsv(filters): ByteArray`.

Reused unchanged: `profiles.ProfileExport.forUser`, `documents.DocumentExport.forOwner`,
`admin.AdminAuthorization`, the admin keyset-cursor pattern.

Module deps: admin → identity, privacy, profiles, documents, audit, platform (all one-way;
ModularityTests green). No module depends on admin.

## Frontend (admin route group)

- `/admin/users` (design 5b): searchable/filterable user list (query, status), keyset "Load
  more", row → card link. Nav item shown only when the admin has USER_VIEW.
- `/admin/users/[id]` (design 11c): the read-only card — header, profile, count cards
  (documents/DSRs/consents/sessions), consent history, sessions list, and the
  "content unavailable to support / every view audited" footer. No action buttons.
- `/admin/audit` (design 11b): the audit table with the filter bar (actor/action/entity/
  period), keyset "Load more", the append-only note, and a "Export CSV" button shown only
  for SUPERADMIN (AUDIT_EXPORT). Nav item shown only when the admin has AUDIT_VIEW.
- Role-gated nav in the admin shell (from `/admin/me` role → permission helper).

## OpenAPI / docs

- New endpoints → refresh the snapshot + `npm run gen:api` (check:api gate).
- Docs: AUDIT_EVENTS.md (ADMIN_USER_LIST_VIEWED, ADMIN_USER_DETAIL_VIEWED,
  ADMIN_AUDIT_LOG_VIEWED), API_GUIDELINES.md (the new endpoints), MODULES.md (admin gains
  identity/profiles/documents/audit read deps).

## Open questions (resolved; none block the plan)

1. **Audit region filter** — none; the cell is the region (physical scoping). Noted.
2. **Card verification-signal count** — deferred (profile verification unbuilt).
3. **Integrity chain** — deferred (no chaining column; append-only by permission).
4. **User actions (reset sessions / disable)** — deferred (read-only this iteration, per
   the user's decision; they need ACCOUNT_BLOCK + the four-eyes design).
5. **CSV export cap** — 10k rows with a truncation note; a streamed/paged export is a
   follow-up if needed.

## Non-negotiables check

- Every admin read of user data + every audit-log read is audited (ADMIN_USER_*/
  ADMIN_AUDIT_LOG_VIEWED), metadata IDs/filters/counts only.
- Support-without-content: the card exposes only metadata; no document/letter/file content.
- Admins cannot modify anything here (read-only; no mutation endpoints).
- RBAC enforced server-side (USER_VIEW/AUDIT_VIEW/AUDIT_EXPORT → 403); region-scoped.
- No object-storage URLs; no PII in audit metadata; `ip_hash`/`ua_hash` never returned.
- Module boundaries one-way (admin → owning modules); ModularityTests green.
- No schema change; OpenAPI refreshed + frontend client regenerated.
