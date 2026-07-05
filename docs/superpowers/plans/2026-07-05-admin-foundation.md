# Admin Foundation + DSR Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admin authentication (magic-link + mandatory TOTP MFA), RBAC, isolated admin session/security chain, config bootstrap, dashboard shell, and the DSR review queue wired to the existing privacy service.

**Architecture:** admin owns admin_* tables + auth; a second SecurityFilterChain scopes `/api/v1/admin/**`; admin calls privacy's new admin read + decision APIs (module boundary). Spec: `docs/superpowers/specs/2026-07-05-admin-foundation-design.md` (schema, flow, RBAC matrix there are normative).

**Tech Stack:** existing Kotlin/Spring/jOOQ + Next.js; new deps: backend `com.eatthepath:java-otp` (+ commons-codec if absent), frontend `react-qr-code`.

## Global Constraints

- Migrations V1–V13 immutable; new schema = `V14__admin.sql`. jOOQ regen after.
- Admin auth fully isolated: separate tables (admin_account, admin_magic_link_token, admin_session, admin_mfa_pending), separate cookie (`verifolio_admin_session`, `verifolio_admin_pending`), separate SecurityFilterChain. An admin credential never yields a user session.
- MFA mandatory: an admin session is minted ONLY after magic-link AND TOTP both pass.
- TOTP secret AES-256-GCM encrypted at rest (`AdminTotpCipher`, key `verifolio.admin.totp-secret-key`); tokens HMAC-hashed (TokenHasher); MFA attempt-capped (atomic claim, DsrVerificationCodes precedent); admin magic-link request always 202 (anti-enum) + rate-limited.
- Every admin read of subject data audited (`ADMIN_DSR_VIEWED`); decisions/executions audited with the ADMIN actor id.
- Admin cannot modify locked versions/signals (no such endpoint; execute() uses the sanctioned privacy path).
- RBAC = code-defined role→permission map (SUPPORT_L1/L2/SUPERADMIN); endpoints gate by permission → 403.
- Region-scoped: admin accounts/sessions in-cell; DSR queue filtered by admin region.
- ModularityTests green (admin → platform/audit/privacy; nothing depends on admin).
- OpenAPI refreshed + `npm run gen:api` committed (check:api gate).
- Integration tests extend testsupport.IntegrationTest; no sleeps. New frontend strings en+ru.

---

### Task 1: V14 + deps + config + jOOQ

**Files:** Create `apps/backend/src/main/resources/db/migration/V14__admin.sql`; Modify `apps/backend/build.gradle.kts` (java-otp + commons-codec if needed), `platform/VerifolioProperties.kt` (`Admin(bootstrapEmails: List<String> = [], totpSecretKey: String = <local default>)`), `application.yaml`.

- [ ] **Step 1:** V14 exactly per spec §Schema (four tables + indexes).
- [ ] **Step 2:** Add `com.eatthepath:java-otp:0.4.0`; verify commons-codec is resolvable (add if not). `Admin` config + yaml (bootstrap-emails empty; totp-secret-key a documented dev default; validate non-default when region != local like the pepper).
- [ ] **Step 3:** `cd apps/backend && ./gradlew generateJooq compileKotlin` OK; ADMIN_ACCOUNT etc. generated. Commit — `feat(backend): V14 admin schema, TOTP dep and config`

---

### Task 2: admin auth core — cipher, tokens, TOTP, sessions

**Files:** Create `admin/domain/AdminRole.kt` (roles + permission map), `admin/AdminActor.kt` (public principal), `admin/application/AdminTotpCipher.kt` (AES-GCM), `admin/application/AdminTotpService.kt` (java-otp generate/verify + otpauth URI), `admin/application/AdminMagicLinks.kt`, `admin/application/AdminSessions.kt`, `admin/application/AdminMfa.kt` (pending + enroll/challenge), `admin/application/AdminBootstrap.kt`; Tests `admin/AdminTotpCipherTest.kt`, `admin/AdminAuthUnitTest.kt`.

**Interfaces (produces):** `AdminActor(adminId, email, role: AdminRole, region)`; `AdminRole.has(permission)`; auth services used by Task 3 controllers.

- [ ] **Step 1:** `AdminTotpCipher` — AES-256-GCM encrypt/decrypt (random 12-byte IV, store `base64(iv):base64(ct)`), key from config. Unit test: round-trip, distinct IV per call, tamper → fail.
- [ ] **Step 2:** `AdminTotpService` — random 20-byte secret, Base32 (commons-codec), otpauth URI per spec, verify with ±1 step skew via java-otp. Unit test: a known secret+time yields a code java-otp accepts; wrong code rejected.
- [ ] **Step 3:** `AdminMagicLinks` (mint/consume, HMAC hash, 15m, single-use, reissue invalidates, always-202 semantics at the caller), `AdminSessions` (mint post-MFA → `verifolio_admin_session`, resolve, revoke; ip/ua hashed), `AdminMfa` (create pending cookie + state; enroll: verify against pending enroll_secret_enc → store on account + mfa_enrolled_at + mint session; challenge: verify against account secret, attempt-capped atomic claim → mint session). `AdminBootstrap` (ApplicationReadyEvent, idempotent, SUPERADMIN, audits ADMIN_ACCOUNT_CREATED).
- [ ] **Step 4:** Compile; unit tests green. Commit — `feat(backend): admin auth core — TOTP cipher, magic links, MFA, sessions, bootstrap`

---

### Task 3: admin security chain + auth endpoints

**Files:** Create `admin/api/AdminSecurityConfig.kt` (@Order(1), securityMatcher /api/v1/admin/**), `admin/api/AdminSessionAuthFilter.kt`, `admin/api/AdminPendingFilter.kt`, `admin/api/AdminAuthController.kt` + DTOs; Modify `identity/api/SecurityConfig.kt` (add @Order(2) to the existing chain); Test `admin/AdminAuthIntegrationTest.kt`.

- [ ] **Step 1:** Admin filter chain per spec (STATELESS; permitAll+CSRF-exempt for magic-links + consume; pending-cookie filter for MFA endpoints; admin-session + CSRF for the rest). Add @Order(2) to identity chain; confirm the user chain no longer needs to match /admin (admin @Order(1) with securityMatcher wins).
- [ ] **Step 2:** `AdminAuthController`: POST magic-links (202), POST magic-links/consume (→ pending cookie + state), GET mfa/enrollment, POST mfa/enroll, POST mfa/verify, GET me, POST logout. Wire the services from Task 2.
- [ ] **Step 3: Integration tests** — bootstrap an admin via config; request magic link (202) + capture via RecordingMailPort; consume → ENROLL state + pending cookie; enrollment returns secret; compute a valid TOTP (use AdminTotpService in-test) → enroll mints admin session; /admin/me returns role SUPERADMIN; logout revokes; second login → CHALLENGE state → verify → session; wrong code attempts cap then pending invalidated; unknown email magic-link → 202 no token; a user-session cookie does NOT authenticate on `/api/v1/admin/**` and an admin-session does NOT authenticate on `/api/v1/auth/**` (isolation); CSRF enforced on an admin mutation.
- [ ] **Step 4:** Green; ModularityTests green. Commit — `feat(backend): admin security chain and authentication endpoints`

---

### Task 4: DSR admin read/decision APIs (privacy) + admin queue endpoints

**Files:** Create `privacy/DataSubjectRequestAdminView.kt` (+ impl) (`listForRegion(region, status?, cursor)`, `get(id, region)`), Modify `privacy/application/DataSubjectRequestService.kt` (approve/reject/execute accept `adminActorId`; map NOT_IMPLEMENTED types → 409 EXECUTION_NOT_AUTOMATED), Create `admin/api/AdminDsrController.kt` + DTOs, `admin/application/AdminDashboardService.kt`, `admin/application/AdminAuthorization.kt` (permission guard); Modify `docs/AUDIT_EVENTS.md` (ADMIN_DSR_VIEWED, ADMIN_ACCOUNT_CREATED), `docs/API_GUIDELINES.md`; Test `admin/AdminDsrQueueIntegrationTest.kt`.

- [ ] **Step 1:** privacy admin read API (keyset cursor, region filter) + service method `adminActorId` params (thread the admin id into the existing DATA_SUBJECT_REQUEST_* audits). `execute()` maps the NOT_IMPLEMENTED throw to ApiException(409, "EXECUTION_NOT_AUTOMATED").
- [ ] **Step 2:** `AdminAuthorization.require(actor, permission)` → 403. `AdminDsrController`: dashboard (DSR counts), list, detail (both audit ADMIN_DSR_VIEWED), approve/reject/execute (permission-gated, pass adminId). Region from `AdminActor`.
- [ ] **Step 3: Integration tests** — as an L1 admin: can list/detail (audit ADMIN_DSR_VIEWED written) but approve → 403; as L2/SUPERADMIN: approve moves RECEIVED→APPROVED with ADMIN actor audit; reject with notes; execute on a recommender-DELETION DSR (drive one to RECEIVED) runs and → EXECUTED; execute on an owner EXPORT → 409 EXECUTION_NOT_AUTOMATED; region scoping (an admin sees only their region's DSRs); dashboard counts. ModularityTests green.
- [ ] **Step 4:** Green; commit — `feat(backend): admin DSR review queue with RBAC and audited reads`

---

### Task 5: OpenAPI + docs + history

- [ ] **Step 1:** `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`; verify all admin endpoints present. Full `./gradlew test --rerun` green.
- [ ] **Step 2:** IMPLEMENTATION_HISTORY iteration-13 entry (shipped: admin auth+MFA+RBAC, bootstrap, DSR queue; deferred: user management, audit-log viewer, org/catalog write, identity-verification queue, the non-automated DSR executors, superadmin step-up, per-role field restrictions). Commit — `docs(backend): OpenAPI + docs for admin foundation`

---

### Task 6: admin frontend — auth

**Files:** Regenerate `apps/frontend/lib/api/schema.d.ts`; add `react-qr-code`; Create `app/(admin)/layout.tsx` (dark admin shell), `app/(admin)/admin/login/page.tsx`, `.../auth/callback/page.tsx`, `.../mfa/enroll/page.tsx`, `.../mfa/challenge/page.tsx`, `lib/admin/api.ts` (admin client instance + CSRF), `lib/admin/use-admin-session.ts`; middleware admin-cookie guard; messages en+ru; Tests.

- [ ] **Step 1:** `npm run gen:api` + add react-qr-code. Admin API client (baseUrl `/`, same CSRF middleware, admin session cookie is HttpOnly so presence is checked server-side via `/admin/me`).
- [ ] **Step 2:** Login (email → 202 copy), callback (consume → route by state), enroll (QR from otpauthUri + secret text + code), challenge (code). Error/attempt states.
- [ ] **Step 3:** RTL tests: login 202 copy; callback routes ENROLL vs CHALLENGE; enroll posts code; challenge posts code + CODE_INVALID. `npm run lint && npm run check:api && npm run test -- --run && npm run build` green. Commit — `feat(frontend): admin login with TOTP enrollment and challenge`

---

### Task 7: admin frontend — dashboard + DSR queue

**Files:** Create `app/(admin)/admin/page.tsx` (dashboard), `app/(admin)/admin/data-requests/page.tsx` (queue), `components/admin/*`; messages en+ru; Tests.

- [ ] **Step 1:** Dashboard: pending DSR count card (design 5a subset) from `GET /admin/dashboard`; admin identity + role from `/admin/me`; logout.
- [ ] **Step 2:** DSR queue (design 5d): list with status filter, detail panel; approve / reject (notes dialog) / execute actions shown per the admin's role (hide/disable what the role lacks; server still enforces); `EXECUTION_NOT_AUTOMATED` → "manual execution required" state.
- [ ] **Step 3:** RTL tests: queue renders + filters; role-gated actions (L1 sees no approve); execute-not-automated message. Full frontend suite + build green. Commit — `feat(frontend): admin dashboard and DSR review queue`

---

### Task 8: E2E + security review + PR

- [ ] **Step 1:** `e2e/admin.spec.ts`: bootstrap admin (config in the compose/test backend — set `verifolio.admin.bootstrap-emails`); admin requests magic link → Mailpit link → consume → ENROLL → the test computes a TOTP from the enrollment secret (mirror how the backend test does it; the secret is shown on the enroll page) → dashboard; open DSR queue (seed a DSR via the public recommender channel or owner channel first) → approve/execute path visible. Second spec or step: re-login uses CHALLENGE.
- [ ] **Step 2:** Local full runs: backend `./gradlew test --rerun`, frontend suite, `npx playwright test` (compose creds per LOCAL_DEVELOPMENT.md; set bootstrap-emails for the local backend).
- [ ] **Step 3:** Dispatch a focused security review of the admin auth surface (isolation, MFA-before-session invariant, secret-at-rest, attempt caps, anti-enum, CSRF, region scoping, no privilege escalation between roles). Fix findings.
- [ ] **Step 4:** Push `feature/admin`, open PR, babysit checks + bot review loop (remember gen:api on any OpenAPI change).

## Self-review notes

- Spec → tasks: schema/deps→T1, auth core→T2, security chain+endpoints→T3, DSR queue+RBAC→T4, OpenAPI/docs→T5, FE auth→T6, FE dashboard+queue→T7, E2E+secreview→T8. Deferred items carry no tasks.
- Invariant to enforce & test: admin session minted ONLY after both factors (T3 tests); admin/user auth isolation (T3 tests); secret encrypted at rest (T2 test); RBAC 403 (T4 test).
- Interface consistency: `AdminActor`/`AdminRole` (T2) used by T3/T4 controllers; `DataSubjectRequestAdminView` (T4) consumed by AdminDsrController; `adminActorId` param added in T4 to existing approve/reject/execute.
