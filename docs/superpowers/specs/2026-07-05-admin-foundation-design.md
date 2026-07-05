# Admin Foundation + DSR Queue — Design

Date: 2026-07-05
Status: approved (user: auth = magic-link + TOTP; scope = foundation + DSR queue, one PR)
Scope: iteration 13 — first real code in the `admin` module. Admin authentication
(magic-link + mandatory TOTP MFA), RBAC, a separate admin session + security filter
chain, config-driven bootstrap, the admin dashboard shell, and the DSR review queue
(list / approve / reject / execute) wired to the existing `privacy` service. Everything
else the admin design shows (user management, audit-log viewer, catalog/org management,
identity-verification queue, the not-yet-implemented DSR executors) is deferred to
follow-up admin iterations.

Normative sources: AUTHENTICATION.md §Admin & Support Access (163-171: separate per
region, mandatory MFA, every admin read of user data audited, cannot modify locked
versions/signals, actions follow domain authorization), PRIVACY_AND_DATA_CLASSIFICATION.md
(admin audit-log access, support-without-content), MODULES.md §admin (201-208),
AUDIT_EVENTS.md (ADMIN actor), ROADMAP.md (admin + DSR execution automation = v1.1),
design canvas 5a/5d/11d.

## Auth model — magic-link + mandatory TOTP (user decision)

Admin login is a two-factor sequence; an admin session is minted ONLY after both factors
pass. Admin auth is fully isolated from user/recommender auth (separate tables, separate
magic-link mechanism, separate cookie, separate SecurityFilterChain) — an admin magic
link can never yield a user session and vice versa.

### Flow

1. `POST /api/v1/admin/auth/magic-links {email}` → **always 202** (anti-enumeration). If
   the email is an ACTIVE `admin_account` in THIS cell, mint an `admin_magic_link_token`
   (HMAC-hashed, 15-min TTL, single-use, reissue invalidates prior) and email a link to
   the frontend `/admin/auth/callback?token=`. Per-email + per-IP sliding-window limits.
2. `POST /api/v1/admin/auth/magic-links/consume {token}` → validates+consumes the token;
   mints a short-lived **pending-MFA** cookie `verifolio_admin_pending` (HttpOnly,
   SameSite=Strict, 5-min TTL, bound to the admin id, NOT an admin session) and returns
   `{state: "ENROLL" | "CHALLENGE"}` (ENROLL if `mfa_enrolled_at` is null).
3. The MFA step branches by that state:
   - **ENROLL** (first login): `GET /api/v1/admin/auth/mfa/enrollment` (pending cookie) →
     `{secretBase32, otpauthUri}` — server generates a fresh 20-byte secret, holds it in
     the pending row (not yet the account's secret). `POST /api/v1/admin/auth/mfa/enroll
     {code}` → verify the TOTP against the pending secret; on success store the secret
     (encrypted, see below) on the admin_account, set `mfa_enrolled_at`, mint the admin
     session, clear pending.
   - **CHALLENGE**: `POST /api/v1/admin/auth/mfa/verify {code}` → verify TOTP against the
     stored secret; on success mint the admin session, clear pending. Attempt-capped
     (5 attempts on the pending challenge, atomic claim like DsrVerificationCodes), then
     the pending cookie is invalidated (restart from step 1).
4. Authenticated: `GET /api/v1/admin/me` → `{id, email, role, region}`;
   `POST /api/v1/admin/auth/logout` (revokes the admin session).

### TOTP specifics

- RFC 6238 TOTP (30s step, 6 digits, SHA-1 — authenticator-app standard) implemented
  **JDK-only** (`javax.crypto.Mac` + an inline RFC 4648 Base32 in `AdminTotpService`) —
  **no third-party dependency** (no `java-otp`, no commons-codec), keeping the module
  deployable into restricted regional networks. Secrets are 20 random bytes, Base32
  (upper-case, unpadded).
- `otpauthUri` = `otpauth://totp/{issuer}:{account}?secret={base32}&issuer=Verifolio`, where the
  label components (`Verifolio ({region})` and the email) and the query values are percent-encoded
  per the otpauth spec, so reserved characters can't break enrollment.
- **Secret at rest is encrypted** (not plaintext — a plaintext MFA seed in the app DB
  would defeat MFA): AES-256-GCM via `AdminTotpCipher` (javax.crypto). The AES-256 key is the
  **SHA-256 digest of the per-cell `verifolio.admin.totp-secret-key`** (an ops-provided secret
  string, not a base64 key blob); a random 12-byte IV per secret is stored alongside the
  ciphertext. No new crypto dependency. `local` default key ships for dev; real cells set their
  own (validated non-default when region != local, mirroring the pepper rule).
- Verification allows ±1 step clock skew.

## RBAC — three fixed roles, code-defined permissions

Roles (design 11d): `SUPPORT_L1`, `SUPPORT_L2`, `SUPERADMIN`. Permissions are a code enum
(a fixed role→permission map in `admin.domain.AdminRole`), not a DB table — three fixed
roles do not warrant DB-driven RBAC, and a code map is versioned/reviewable.

| Permission | L1 | L2 | SUPERADMIN |
|---|---|---|---|
| `DSR_VIEW` (list/detail the queue) | ✓ | ✓ | ✓ |
| `DSR_DECIDE` (approve/reject) | | ✓ | ✓ |
| `DSR_EXECUTE` (run execution) | | ✓ | ✓ |
| `ADMIN_MANAGE` (future: manage admins/roles) | | | ✓ |

Endpoints declare a required permission; an `AdminAuthorization` check (from the admin
principal's role) throws 403 `FORBIDDEN` otherwise. The "Superadmin 2-step confirmation
of critical actions" from the design is noted but deferred (step-up is a tracked deferred
item); this iteration gates by role only.

## Bootstrap — config-driven, idempotent

No admin exists initially and there is no manual-SQL story. On `ApplicationReadyEvent`,
`AdminBootstrap` reads `verifolio.admin.bootstrap-emails` (list). For each email with no
existing `admin_account` in this cell: find-or-create a `user_account` (region = cell
region) and insert an `admin_account` (role SUPERADMIN, status ACTIVE, `mfa_enrolled_at`
null → must enroll on first login). Idempotent (skips existing). Audited
`ADMIN_ACCOUNT_CREATED` (actor SYSTEM). Empty/unset list = no bootstrap (prod sets it per
cell). This is the ONLY way to create the first admin this iteration; admin-managed
account creation arrives with ADMIN_MANAGE.

## Schema (Flyway V14)

```sql
create table admin_account (
  id              uuid primary key default gen_random_uuid(),
  user_account_id uuid not null unique references user_account(id),
  email           text not null,                    -- denormalized for login lookup
  region          text not null,                    -- must equal the cell region
  role            text not null check (role in ('SUPPORT_L1','SUPPORT_L2','SUPERADMIN')),
  status          text not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
  totp_secret_enc text,                              -- AES-GCM(base64 iv:ct); null until enrolled
  mfa_enrolled_at timestamptz,
  created_at      timestamptz not null default transaction_timestamp(),
  updated_at      timestamptz not null default transaction_timestamp()
);
create unique index admin_account_email_unique on admin_account (lower(email));

create table admin_magic_link_token (
  id          uuid primary key default gen_random_uuid(),
  email       text not null,
  token_hash  text not null,
  expires_at  timestamptz not null,
  consumed_at timestamptz,
  invalidated_at timestamptz,
  created_at  timestamptz not null default transaction_timestamp()
);
create index admin_magic_link_token_hash_idx on admin_magic_link_token (token_hash);

create table admin_session (
  id              uuid primary key default gen_random_uuid(),
  admin_account_id uuid not null references admin_account(id),
  token_hash      text not null,
  ip_hash         text,
  user_agent_hash text,
  expires_at      timestamptz not null,
  revoked_at      timestamptz,
  last_seen_at    timestamptz,
  created_at      timestamptz not null default transaction_timestamp()
);
create index admin_session_token_hash_idx on admin_session (token_hash);

-- Pending MFA (post-magic-link, pre-session): holds the challenge state and, during
-- enrollment, the not-yet-committed secret. Short TTL, attempt-capped.
create table admin_mfa_pending (
  id              uuid primary key default gen_random_uuid(),
  admin_account_id uuid not null references admin_account(id),
  token_hash      text not null,                    -- the verifolio_admin_pending cookie value
  enroll_secret_enc text,                           -- AES-GCM; set only in ENROLL flow
  attempts        int not null default 0,
  expires_at      timestamptz not null,
  consumed_at     timestamptz,
  created_at      timestamptz not null default transaction_timestamp()
);
create index admin_mfa_pending_token_hash_idx on admin_mfa_pending (token_hash);
```

Tokens/cookies hashed via `TokenHasher` (platform); TOTP secrets encrypted via
`AdminTotpCipher`. IP/UA hashed like user_session.

## Admin SecurityFilterChain (isolated)

New `AdminSecurityConfig` — a second `SecurityFilterChain @Order(1)` with
`securityMatcher("/api/v1/admin/**")`; the existing user chain gets `@Order(2)` (one-line
edit to identity SecurityConfig). Admin chain:

- STATELESS; `AdminSessionAuthFilter` resolves `verifolio_admin_session` → `AdminActor`
  principal (`adminId`, `role`, `region`); a `AdminPendingFilter` resolves the pending
  cookie for the MFA endpoints only.
- permitAll + CSRF-exempt: `/api/v1/admin/auth/magic-links`,
  `/api/v1/admin/auth/magic-links/consume`. MFA endpoints require the pending cookie
  (their own filter), CSRF-exempt (pre-session). Everything else under `/api/v1/admin/**`
  requires an admin session + CSRF (same XSRF-TOKEN cookie mechanism — the admin frontend
  reads it and sends X-XSRF-TOKEN).
- `AdminActor` is admin-module public API (like `AuthenticatedUser`), injected via
  `@AuthenticationPrincipal`.

## DSR review queue (admin module, region-scoped)

Every endpoint requires an admin session + the listed permission; the admin's region
scopes all reads (an admin sees only their cell's DSRs — cell isolation is already
physical, but the query filters by region defensively).

| Endpoint | Perm | Backing |
|---|---|---|
| `GET /api/v1/admin/dashboard` | DSR_VIEW | pending DSR counts by status (region); other counters (users/verification/tickets) return 0/absent this iteration |
| `GET /api/v1/admin/data-subject-requests?status=&cursor=` | DSR_VIEW | keyset-cursor list; audits `ADMIN_DSR_VIEWED` (actor ADMIN, IDs only) |
| `GET /api/v1/admin/data-subject-requests/{id}` | DSR_VIEW | detail; audits `ADMIN_DSR_VIEWED` (every admin read of subject data is audited) |
| `POST /api/v1/admin/data-subject-requests/{id}/approve` | DSR_DECIDE | `DataSubjectRequestService.approve(id, adminId)` (exists) |
| `POST /api/v1/admin/data-subject-requests/{id}/reject {notes}` | DSR_DECIDE | `.reject(id, adminId, notes)` (exists) |
| `POST /api/v1/admin/data-subject-requests/{id}/execute` | DSR_EXECUTE | `.execute(id)` (exists) |

- New public API on privacy: `DataSubjectRequestAdminView` — `listForRegion(region,
  status?, cursor)` + `get(id, region)` returning an admin-facing DTO. The privacy module
  owns its tables; admin calls this read API rather than touching DSR tables directly
  (module boundary). The approve/reject/execute service methods gain an `adminActorId`
  param so the audit records the acting admin.
- `execute()` on a NOT_IMPLEMENTED type (EXPORT / account-DELETION / REGION_MIGRATION /
  CORRECTION) currently throws — mapped to **409 `EXECUTION_NOT_AUTOMATED`** so the admin
  UI shows "manual execution required, automation pending" rather than a 500. The
  automated executors are a later DSR iteration; this queue lets admins review, decide,
  and execute the types that ARE automated (recommender-DELETION; CONSENT_WITHDRAWAL is
  already auto at intake).
- Admin action audits carry the ADMIN actor id (the service already emits the DSR
  lifecycle events; the controller/service passes the admin id through).

## Module boundaries

admin depends on: platform, audit, privacy (DSR admin read + decision APIs), identity
(TokenHasher/pepper via platform; AuthenticatedUser not needed — admin has its own
principal). admin owns admin_* tables. Nothing depends on admin. ModularityTests must
stay green (`admin` is already in the expected set).

## Frontend (admin route group)

A new `app/(admin)/*` route group with its own dark shell (design 5a) and a separate
API client instance targeting `/api/v1/admin/*` with the admin session cookie + CSRF.

- `/admin/login` — email → 202 "if this is an admin address, a link is on its way".
- `/admin/auth/callback?token=` — consume → route to enroll or challenge.
- `/admin/mfa/enroll` — show the otpauth QR (via `react-qr-code`, tiny) + the base32
  secret for manual entry + a 6-digit code field → enroll.
- `/admin/mfa/challenge` — 6-digit code field → verify.
- `/admin` — dashboard shell: pending DSR count card (design 5a subset).
- `/admin/data-requests` — the queue (design 5d): list with status filter, row detail,
  approve / reject (with notes) / execute actions gated by the current admin's role
  (fetched from `/admin/me`); the "manual execution required" state for non-automated
  types. Every list/detail view hits the audited backend read.
- Middleware/UX guard: `(admin)` routes check the admin session cookie presence and
  redirect to `/admin/login`; real authorization stays server-side.

## Non-negotiables check

- Admin auth fully isolated (separate tables/cookie/filter chain/magic-link) — an admin
  credential never grants user access. MFA mandatory (session minted only post-TOTP).
- Every admin read of subject data audited (`ADMIN_DSR_VIEWED`); every decision/execution
  audited with the ADMIN actor id.
- Admin cannot modify locked versions or verification signals — this iteration exposes no
  such mutation; the DSR execute path runs the existing privacy erasure/tombstone logic
  (which is the sanctioned path), never a direct signal/version edit.
- TOTP secret encrypted at rest; tokens HMAC-hashed; anti-enumeration 202 on admin
  magic-link request; attempt-capped MFA.
- Region: admin accounts + sessions live in-cell; the DSR queue is region-scoped; no
  cross-region admin identity.
- Flyway V14 only; V1–V13 untouched. OpenAPI + frontend client regenerated (run
  `npm run gen:api` after the snapshot refresh — the recurring CI gotcha).
- New deps: backend **none** (TOTP + Base32 are JDK-only, see §TOTP specifics);
  frontend `react-qr-code` (minimal, widely used, MIT).

## Open questions (resolved; none block the plan)

1. **Where the admin frontend is served** — recommendation: same Next app, `(admin)`
   route group, same origin/proxy (admin API is under `/api/v1/admin`, proxied like the
   rest). A separate admin deployment is over-engineering for v1.
2. **Support-without-content field restrictions** — recommendation: the DSR admin DTO
   this iteration exposes subject email + type + status + timestamps + resolution notes,
   NOT letter/answer/upload content (none of which the DSR row holds anyway). The precise
   Support-L1-vs-L2 field matrix lands with the user-management iteration.
3. **`execute()` for non-automated types** — recommendation: 409 EXECUTION_NOT_AUTOMATED
   now; the automated executors (EXPORT/account-DELETION/REGION_MIGRATION) are a scoped
   later DSR iteration with async jobs.
4. **Superadmin 2-step confirmation** — deferred (step-up item); role gating only now.
