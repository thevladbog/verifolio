# Privacy / DSR Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** First real code in the `privacy` module: DSR intake (both channels) + hybrid execution, recommender PII erasure on decline, retraction/consent withdrawal, tombstoning, and the public-page/frontend states.

**Architecture:** privacy module orchestrates via new public APIs of owning modules (`requests.RecommenderPiiErasure`, `documents.DocumentRetraction`, `documents.DocumentTombstone`, existing `verification.VerificationSignals.markRevoked`); scheduled erasure rides the `workflows.RecurringTask` fallback. Spec: `docs/superpowers/specs/2026-07-05-privacy-dsr-core-design.md` — the erasure matrix and V11 DDL there are normative.

**Tech Stack:** existing backend/frontend stacks; no new dependencies.

## Global Constraints

- V11 only; V1–V10 immutable. jOOQ regen after migration.
- Locked-version mutations: ONLY `retracted_at` set (retraction) and the tombstone path (content NULL + status TOMBSTONED). Nothing else, enforced by tests.
- S3 deletes outside DB transactions, DB status flips only after storage success (cleanup-task precedent).
- Audit metadata: IDs/enums/counts only. New events `RECOMMENDER_PII_ERASED`, `RECOMMENDATION_RETRACTED`, `DOCUMENT_VERSION_TOMBSTONED`, `DATA_SUBJECT_REQUEST_*` — AUDIT_EVENTS.md updated.
- Anti-enumeration: public recommender channel always 202; codes HMAC-hashed (TokenHasher), TTL 10 min, 5 attempts (attempts++ REQUIRES_NEW), resend 3/15 min, per-IP 100/15 min.
- ModularityTests must stay green (privacy → requests/files/documents/verification/workflows/platform only).
- OpenAPI refreshed + frontend client regenerated in the same PR; integration tests extend `testsupport.IntegrationTest`, no sleeps (tasks invoked directly).
- All new frontend strings en+ru.

---

### Task 1: V11 + config + jOOQ

**Files:** Create `apps/backend/src/main/resources/db/migration/V11__privacy_dsr.sql` (DDL verbatim from spec §Schema); Modify `platform/VerifolioProperties.kt` (`Privacy(sla: Duration = 30d, declineErasureGrace: Duration = 24h)`), `application.yaml` mirrors.

- [ ] **Step 1:** Migration file exactly per spec (both tables, three ALTERs).
- [ ] **Step 2:** Config + yaml.
- [ ] **Step 3:** `./gradlew generateJooq compileKotlin` OK. Commit — `feat(backend): V11 privacy/DSR schema and config`

---

### Task 2: requests.RecommenderPiiErasure + files system delete

**Files:** Create `requests/RecommenderPiiErasure.kt` (public API: `eraseForRequest(requestId: UUID): ErasureSummary(requestId, responsesDeleted, uploadsDeleted, tokensScrubbed, sessionsDeleted)`), `requests/application/RecommenderPiiErasureImpl.kt`; Modify `files/FileUploads.kt` + impl (`deleteUploadAsSystem(fileId)` — actor SYSTEM audit), owner request DTO (`recommenderPiiErasedAt`), `docs/AUDIT_EVENTS.md`, `docs/DATA_MODEL.md`; Test `requests/RecommenderPiiErasureTest.kt`.

**Erasure matrix (implement exactly, spec table is normative):** null request snapshot columns + set `recommender_pii_erased_at`; delete `reference_response` rows; delete unattached uploads' S3 objects then rows→DELETED via `deleteUploadAsSystem`; null `invitation_token.recommender_email`; delete `recommender_session` + `email_confirmation_code` rows; DO NOT touch contact/consent/attachments/audit. Audit `RECOMMENDER_PII_ERASED` (SYSTEM, counts).

- [ ] **Step 1:** Implement API + impl (idempotent: second call no-ops on `recommender_pii_erased_at IS NOT NULL`).
- [ ] **Step 2: Integration tests** — drive a request through decline with an uploaded file; erase; assert every matrix row (columns null, rows gone, MinIO object gone, contact untouched, consent rows untouched, audit with counts); idempotency; owner GET shows `recommenderPiiErasedAt`.
- [ ] **Step 3:** Green; commit — `feat(backend): recommender PII erasure service`

---

### Task 3: documents retraction + tombstone + public page states (backend)

**Files:** Create `documents/DocumentRetraction.kt` (`markRetracted(requestId): Int`), `documents/DocumentTombstone.kt` (`tombstone(versionId)`), impls in `documents/application/`; Modify `files` (expose physical delete for generated PDFs/attachments — extend `FileStore` with `deleteObjectAsSystem(fileId)` or reuse FileUploads overload, pick per existing layering), `publicpages/application/PublicVerificationPageService.kt` + DTOs (`retractedAt`, tombstoned shape: header + notice only), `docs/PUBLIC_VERIFICATION_PAGE.md` (mark states implemented); Tests `documents/RetractionTombstoneTest.kt`, extend `publicpages` tests.

- [ ] **Step 1:** `markRetracted` — set `retracted_at` on the request's versions (only null→value; repeat no-op), audit `RECOMMENDATION_RETRACTED` (metadata: requestId, versionIds count).
- [ ] **Step 2:** `tombstone(versionId)` — S3-delete PDF + attachment objects first (files module), then NULL content columns + status TOMBSTONED + `tombstoned_at`, audit `DOCUMENT_VERSION_TOMBSTONED`. Assert in test that sha256/version_number/locked_at survive.
- [ ] **Step 3:** Public page: retracted → banner data (`retractedAt`) + signals shown REVOKED (they already read live status) + PDF still downloadable; tombstoned → `{status: TOMBSTONED, notice}` shape, no signals/downloads/persons; download-url endpoints → 404 for tombstoned.
- [ ] **Step 4:** Integration tests: retraction page shape; tombstoned page shape; tombstoned download 404; locked content untouched by retraction. Green; commit — `feat(backend): document retraction and tombstoning with public page states`

---

### Task 4: privacy module — DSR service, channels, hybrid execution, erasure task

**Files:** Create `privacy/api/DataSubjectRequestController.kt` (owner endpoints), `privacy/api/RecommenderDsrController.kt` (public channel), `privacy/application/DataSubjectRequestService.kt`, `privacy/application/DsrVerificationCodes.kt`, `privacy/application/RecommenderPiiErasureTask.kt` (RecurringTask), `privacy/application/ConsentWithdrawalExecutor.kt`; Modify `identity/api/SecurityConfig.kt` (`/api/v1/privacy/recommender-requests/**` public + CSRF-exempt; `/api/v1/privacy/**` otherwise authenticated), consent withdrawal touch on `consent_record` (via jOOQ in privacy? NO — consents live in requests module tables; add `requests` public API `ConsentWithdrawal.withdrawForRequest(requestId, contactId): Int`), `docs/API_GUIDELINES.md`, `docs/SECURITY.md` (step-up deferred note); Tests `privacy/DataSubjectRequestIntegrationTest.kt`, `privacy/RecommenderDsrChannelTest.kt`, `privacy/ErasureTaskTest.kt`.

**Endpoints:** per spec §DSR intake (owner submit+list; public request-code 202 + verify). Lifecycle: RECEIVED→(IN_REVIEW→APPROVED)→EXECUTED/REJECTED transitions in service (validated matrix like ReferenceRequestStatus); `due_at = now + props.privacy.sla`.

**Hybrid execution:** `execute(id)` service method (no HTTP): CONSENT_WITHDRAWAL auto-invoked at verify — orchestrates `ConsentWithdrawal.withdrawForRequest` + `VerificationSignals.markRevoked` + `DocumentRetraction.markRetracted` + `RecommenderPiiErasure.eraseForRequest`; DELETION (recommender subject, request-scoped) — tombstone versions + erasure, integration-tested, invoked only via service.

**Erasure task:** per spec (DECLINED + grace elapsed + not yet erased → erase; per-row transactions).

- [ ] **Step 1:** requests `ConsentWithdrawal` public API (GRANTED→WITHDRAWN + withdrawn_at, count returned; audits CONSENT_WITHDRAWN — add to catalog).
- [ ] **Step 2:** privacy service + controllers + codes + limiters; audits DATA_SUBJECT_REQUEST_RECEIVED/EXECUTED (+APPROVED/REJECTED transitions for completeness).
- [ ] **Step 3:** ErasureTask + registration; disabled in tests (workflows.enabled=false), invoked directly.
- [ ] **Step 4: Integration tests** — owner submits DELETION → RECEIVED, due_at = +30d, audit; list; recommender channel: 202 for unknown email (no code row), known email → code in Mailpit → verify CONSENT_WITHDRAWAL → consents WITHDRAWN, signals REVOKED, versions retracted, PII erased, DSR EXECUTED, all audits; wrong code ×5 → locked out; task: declined request +25h → erased, +1h → untouched; ModularityTests green.
- [ ] **Step 5:** Green; commit — `feat(backend): privacy module — DSR intake, consent withdrawal execution, erasure task`

---

### Task 5: OpenAPI + docs + history

- [ ] **Step 1:** `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`; full `./gradlew test --rerun` green.
- [ ] **Step 2:** IMPLEMENTATION_HISTORY iteration-11 entry (shipped + deferred: export/account-deletion/region-migration executors, admin review UI, SLA-breach sweep, step-up). Commit — `docs(backend): OpenAPI + docs for privacy/DSR core`

---

### Task 6: frontend

**Files:** Regen `lib/api/schema.d.ts`; Modify `app/(public)/verify/[token]/page.tsx` (+components) — retracted banner ("Recommendation retracted by the recommender on {date}"), tombstoned state (notice only); Modify `app/(app)/profile/page.tsx` — "Data & privacy" card: DSR type select + submit, own DSR list (status badge, due date); Create `app/(public)/data-requests/page.tsx` — email → 202 state → code+type form → done states; link from verify-page privacy notice + respond footer; messages en+ru; Tests: verify states, profile DSR submit/list, data-requests happy path + CODE_INVALID.

- [ ] **Step 1:** `npm run gen:api`; implement per spec §Frontend.
- [ ] **Step 2:** RTL tests; `npm run lint && npm run check:api && npm run test -- --run && npm run build` green. Commit — `feat(frontend): privacy states, DSR intake UI, recommender data-requests page`

---

### Task 7: E2E + PR

- [ ] **Step 1:** New `e2e/privacy.spec.ts`: full retraction journey — canonical flow to COMPLETED + share link → recommender opens /data-requests → Mailpit code → CONSENT_WITHDRAWAL → public page shows retracted banner + revoked signals; owner detail shows erased recommender placeholder. Second scenario: owner submits DELETION DSR from profile → appears in list RECEIVED.
- [ ] **Step 2:** Local: backend `./gradlew test --rerun`, frontend suite, `npx playwright test` (compose MinIO creds per LOCAL_DEVELOPMENT.md).
- [ ] **Step 3:** Push `feature/privacy-dsr`, open PR, babysit checks + bot review loop.

## Self-review notes

- Spec sections → tasks: V11→T1, erasure matrix+trigger→T2+T4(task), retraction→T3+T4(executor), tombstone→T3, intake/lifecycle→T4, public/frontend states→T3+T6, E2E→T7. Deferred items carry no tasks.
- Interface consistency: `RecommenderPiiErasure.eraseForRequest` (T2) called by T4's executor+task; `DocumentRetraction/DocumentTombstone` (T3) called by T4; `ConsentWithdrawal` (T4 Step 1) precedes its executor use; DTO field `recommenderPiiErasedAt` (T2) consumed by T6.
