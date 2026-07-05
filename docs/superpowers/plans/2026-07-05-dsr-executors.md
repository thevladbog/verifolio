# DSR Executors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement synchronous EXPORT (JSON metadata + emailed presigned link) and account-holder DELETION DSR executors, replacing the `409 EXECUTION_NOT_AUTOMATED` branches.

**Architecture:** Both executors run inside `DataSubjectRequestService.execute()` (admin-triggered). privacy gains new one-way read/erasure ports on identity/profiles/contacts/requests/documents/audit and reuses `documents.DocumentTombstone` + `files.FileStore`. Spec: `docs/superpowers/specs/2026-07-05-dsr-executors-design.md` (matrices there are normative).

**Tech Stack:** existing Kotlin/Spring/jOOQ + Next.js; no new deps.

## Global Constraints

- Migrations V1–V14 immutable; new schema = `V15__dsr_executors.sql`. jOOQ regen after.
- Module boundaries stay one-way: privacy → identity/profiles/contacts/requests/documents/files/audit/verification/workflows/platform. Nothing depends on privacy. ModularityTests must stay green.
- Locked versions change ONLY via `documents.DocumentTombstone` (NULL content + TOMBSTONED, retain sha256/version/lockedAt). Retraction/tombstone are the only sanctioned mutations.
- Audit rows are NEVER deleted; account deletion pseudonymizes `actor_id` (→ null). Consent records retained.
- The only object-storage URL exposure is the subject's own short-lived presigned export link (emailed, TTL-bounded, never logged, not a public page).
- New audit events `DATA_EXPORTED`, `ACCOUNT_DELETED` — metadata IDs/counts/enums only, no email/content.
- Executors synchronous + idempotent; run only via admin `execute()` (DSR_EXECUTE, region-scoped).
- OpenAPI refreshed + `npm run gen:api` committed only if a DTO shape changes (check:api gate).
- Integration tests extend `testsupport.IntegrationTest`; no sleeps. Testcontainers via podman (set DOCKER_HOST + TESTCONTAINERS_RYUK_DISABLED).

---

### Task 1: V15 + config + jOOQ

**Files:** Create `V15__dsr_executors.sql`; Modify `platform/VerifolioProperties.kt` (`Privacy.exportLinkTtl: Duration = 7d`), `application.yaml`, `docs/DATA_MODEL.md`.

- [ ] **Step 1:** Read V6 for the file_object purpose constraint's actual name/form; write V15 to add `DATA_EXPORT` to the purpose check (recreate the constraint), add `user_account.deleted_at timestamptz`, add `data_subject_request.export_file_id uuid references file_object(id)`.
- [ ] **Step 2:** `Privacy.exportLinkTtl` config + yaml.
- [ ] **Step 3:** `cd apps/backend && ./gradlew generateJooq compileKotlin` OK (DOCKER_HOST set). Commit — `feat(backend): V15 DSR-executor schema and export config`

---

### Task 2: export read ports + files.storeExport

**Files:** Create `identity/AccountExport.kt`, `profiles/ProfileExport.kt`, `contacts/ContactExport.kt`, `requests/RequestExport.kt`, `documents/DocumentExport.kt` (+ impls in each module's `application`); Modify `files/FileStore.kt` + impl (`storeExport(bytes): FileRef` → DATA_EXPORT purpose); Tests per module (small read tests).

**Interfaces (produce):** each port returns a serializable metadata DTO for the subject (see spec §EXPORT table). `RequestExport` has `forRequester(profileId)` + `forRecommenderEmail(email)`; `DocumentExport.forOwner(profileId)`; `ProfileExport.forUser(userId)`; `AccountExport.forUser(userId)`; `ContactExport.forOwner(profileId)`. `files.FileStore.storeExport(bytes: ByteArray): FileRef`.

- [ ] **Step 1:** Add each read port (package-root interface + `@Service internal` impl, owner/subject-scoped jOOQ reads, metadata only — no letter/answer content). `storeExport` mirrors `storeGeneratedPdf` with the DATA_EXPORT purpose.
- [ ] **Step 2:** Integration tests: each port returns the seeded subject's rows and nothing else (owner isolation). Compile + `--tests "*Export*" --tests "*Modularity*"` green (privacy not yet wired; these are module-local reads).
- [ ] **Step 3:** Commit — `feat(backend): subject export read ports and files.storeExport`

---

### Task 3: EXPORT executor + wire execute()

**Files:** Create `privacy/application/ExportExecutor.kt`, `privacy/application/ExportPackage.kt` (the JSON model); Modify `privacy/application/DataSubjectRequestService.kt` (EXPORT branch), `docs/AUDIT_EVENTS.md` (DATA_EXPORTED), `docs/PRIVACY_AND_DATA_CLASSIFICATION.md` (export contents); Test `privacy/ExportExecutorIntegrationTest.kt`.

- [ ] **Step 1:** `ExportExecutor.execute(dsr)`: resolve subject → assemble `ExportPackage` from the Task-2 ports (+ privacy's own consent/DSR reads) → serialize pretty JSON → `storeExport` → `presignedDownloadUrl(ttl=exportLinkTtl)` → `mail.send(subjectEmail, link)` → set `export_file_id` → audit `DATA_EXPORTED`. Recommender subject → thinner package (RequestExport.forRecommenderEmail; omit account/profile/contacts).
- [ ] **Step 2:** In `execute()`, EXPORT → `exportExecutor.execute(dsr)` then transition EXECUTED (was 409).
- [ ] **Step 3: Integration tests** — an account holder with a profile, a contact, a sent request, a locked document, a consent submits+admin-executes an EXPORT: a DATA_EXPORT FileObject exists, the mail recording port captured a presigned link, the JSON (fetch the stored bytes) contains the account/profile/contacts/requests/documents/consents sections with the seeded metadata and NO letter content, `export_file_id` set, DATA_EXPORTED audited, DSR EXECUTED. A recommender-subject EXPORT produces the thin package. ModularityTests green (privacy→identity/profiles/contacts new deps).
- [ ] **Step 4:** Commit — `feat(backend): EXPORT executor (JSON package + emailed presigned link)`

---

### Task 4: owner erasure ports + audit pseudonymizer

**Files:** Create `profiles/ProfileErasure.kt`, `contacts/ContactErasure.kt`, `identity/AccountErasure.kt`, `documents/OwnerErasure.kt`, `audit/AuditPseudonymizer.kt` (+ impls); Modify `docs/DATA_MODEL.md`; Tests per module.

**Interfaces (produce):** `profiles.ProfileErasure.eraseForUser(userId)` (anonymize PII, keep row); `contacts.ContactErasure.eraseForOwner(profileId): Int` (anonymize name/email/company); `identity.AccountErasure.eraseForUser(userId)` (status DELETED + deleted_at + email→tombstone; delete sessions + magic links); `documents.OwnerErasure.tombstoneForOwner(profileId): List<UUID>` (tombstone all versions of the owner's documents via the existing DocumentTombstone; returns tombstoned version ids); `audit.AuditPseudonymizer.pseudonymizeActor(actorId): Int` (null actor_id on matching audit_event rows).

- [ ] **Step 1:** Implement each. `OwnerErasure` resolves the owner's document version ids and calls `DocumentTombstone.tombstone` per version (S3-delete-then-DB per the tombstone impl). `AccountErasure` anonymizes email to `deleted-<userId>@tombstone.invalid`. All idempotent (re-run = no-op / zero).
- [ ] **Step 2:** Integration tests: profile anonymized (PII null, row present); contacts anonymized; account status DELETED + email tombstoned + sessions gone; owner documents tombstoned (content null, hash retained); audit actor pseudonymized (rows present, actor_id null). Owner isolation (only the subject's rows). `--tests "*Erasure*" --tests "*Pseudonym*" --tests "*Modularity*"` green.
- [ ] **Step 3:** Commit — `feat(backend): owner erasure ports and audit pseudonymizer`

---

### Task 5: account-holder DELETION executor + wire execute()

**Files:** Create `privacy/application/AccountDeletionExecutor.kt`; Modify `privacy/application/DataSubjectRequestService.kt` (DELETION user-scoped branch), `docs/AUDIT_EVENTS.md` (ACCOUNT_DELETED), `docs/PRIVACY_AND_DATA_CLASSIFICATION.md` (account-deletion matrix); Test `privacy/AccountDeletionIntegrationTest.kt`.

- [ ] **Step 1:** `AccountDeletionExecutor.execute(dsr)` per the spec matrix: `OwnerErasure.tombstoneForOwner` → `ProfileErasure.eraseForUser` → `ContactErasure.eraseForOwner` → `AccountErasure.eraseForUser` → `AuditPseudonymizer.pseudonymizeActor` → audit `ACCOUNT_DELETED`. Consent retained. Idempotent.
- [ ] **Step 2:** `execute()`: `DELETION && user_id != null` → `accountDeletionExecutor.execute(dsr)` then EXECUTED (was 409); recommender-scoped DELETION unchanged; REGION_MIGRATION/CORRECTION keep 409.
- [ ] **Step 3: Integration tests** — an account holder with profile/contacts/request/locked-document/consent/audit submits+admin-executes a DELETION: documents tombstoned (hash retained), profile+contacts anonymized, user_account DELETED + email tombstoned + sessions gone, consent RETAINED, audit actor pseudonymized, ACCOUNT_DELETED audited, DSR EXECUTED. Re-execute is a no-op. Recommender-DELETION path still works (regression). ModularityTests green.
- [ ] **Step 4:** Commit — `feat(backend): account-holder DELETION executor`

---

### Task 6: OpenAPI + docs + history + full suite

**Files:** Modify `apps/backend/api/openapi.yaml` (only if a DTO changed — likely add `exportFileId` to admin DSR detail), `docs/agent/IMPLEMENTATION_HISTORY.md` (iteration-14 entry), `docs/API_GUIDELINES.md`.

- [ ] **Step 1:** If the admin DSR detail exposes `exportFileId`, add it + `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`. Otherwise leave the snapshot.
- [ ] **Step 2:** Full `./gradlew test --rerun` green. IMPLEMENTATION_HISTORY iteration-14 entry (shipped: EXPORT + account-DELETION executors, export read/erasure ports, DATA_EXPORT purpose; deferred: audit-in-export, REGION_MIGRATION/CORRECTION executors, async job orchestration, orphan-reconciliation sweep, audit-retention window). Commit — `docs(backend): OpenAPI + docs for DSR executors`

---

### Task 7: frontend (minimal) + E2E + PR

**Files:** Modify (if `exportFileId` added) `apps/frontend/lib/api/schema.d.ts` (`npm run gen:api`) + the admin DSR detail to show the executed-type outcome copy ("Export delivered" / "Account deleted"); Modify `apps/frontend/messages/{en,ru}.json`; Extend `e2e/admin.spec.ts` or `e2e/privacy.spec.ts`.

- [ ] **Step 1:** If schema changed: `npm run gen:api`; the admin queue detail shows a success/outcome line when a DSR is EXECUTED for EXPORT/DELETION (the Execute button already exists; the 409 "manual required" state no longer fires for these types). Add en+ru copy. RTL: execute EXPORT → success outcome (mock 200 EXECUTED); no more manual-required for these types.
- [ ] **Step 2:** E2E: as a logged-in owner submit an EXPORT DSR from `/profile`, then as the bootstrapped admin open the queue, Execute it → EXECUTED; assert the Mailpit inbox for the subject received the export link. (Account DELETION E2E optional — heavier; a backend integration test covers it; if included, verify the account can no longer log in.) Frontend suite + `npx playwright test` green.
- [ ] **Step 3:** `npm run lint && npm run check:api && npm run test -- --run && npm run build` green. Push `feature/dsr-executors`, open PR, babysit checks + bot review loop (remember: no new Maven deps; run `npm run gen:api` on any OpenAPI change; watch GitGuardian for high-entropy test literals).

## Self-review notes

- Spec → tasks: V15→T1, export ports→T2, EXPORT executor→T3, erasure ports→T4, DELETION executor→T5, OpenAPI/docs→T6, FE+E2E→T7. REGION_MIGRATION/CORRECTION stay 409 (no task).
- Reuse: `DocumentTombstone` (T4 OwnerErasure), `FileStore.presignedDownloadUrl` + `MailPort` (T3), the existing execute()/admin queue (T3/T5 wiring).
- Interface consistency: Task-2 export ports consumed by T3; Task-4 erasure ports consumed by T5; both executors wired into the single `execute()` switch.
- Module deps to verify green in ModularityTests: privacy → identity, profiles, contacts (new in T3/T5), audit (AuditPseudonymizer in T4).
