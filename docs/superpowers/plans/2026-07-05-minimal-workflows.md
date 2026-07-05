# Minimal Workflows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reminder schedule (day 3/7/14) and auto-EXPIRED (day 21) for reference requests plus PENDING-upload and expired-share-link-signal cleanups, on the ADR-0005 DB-scheduler fallback.

**Architecture:** `workflows` owns generic scheduler infra (`RecurringTask` + runner, no domain logic); requests/files/documents implement task beans. Tests disable the runner and invoke `run()` directly. Spec: `docs/superpowers/specs/2026-07-05-minimal-workflows-design.md`.

**Tech Stack:** Spring TaskScheduler; existing stack. No new dependencies.

## Global Constraints

- No Temporal APIs; domain logic behind `RecurringTask` (ADR-0005 fallback rule).
- `run()` idempotent; exceptions logged, never propagate; reminders increment only after a successful send (rollback ⇒ retry next tick).
- New audit actions `REFERENCE_REQUEST_REMINDER_SENT` (SYSTEM), `REMINDERS_STOPPED` (RECOMMENDER) — AUDIT_EVENTS.md updated.
- Scheduler disabled in integration tests (`verifolio.workflows.enabled=false` via IntegrationTest); tests manipulate timestamps and call tasks directly — no sleeps.
- All recommender emails now include the one-click stop-reminders link.
- OpenAPI snapshot refreshed (stop-reminders endpoint).

---

### Task 1: V9 + config + sent_at

**Files:** Create `V9__request_lifecycle.sql`; Modify `VerifolioProperties.kt`, `application.yaml`, `requests/application/ReferenceRequestService.kt` (send sets SENT_AT), `testsupport/IntegrationTest.kt` (`verifolio.workflows.enabled=false`).

- [ ] **Step 1: Migration**

```sql
alter table reference_request add column sent_at timestamptz;
alter table reference_request add column reminders_sent int not null default 0;
alter table reference_request add column reminders_stopped_at timestamptz;
```

- [ ] **Step 2: Config** — `Workflows(enabled: Boolean = true, tickInterval: Duration = 1m, reminderOffsets: List<Duration> = [3d,7d,14d], cleanupInterval: Duration = 1h, pendingUploadTtl: Duration = 24h)` with `init { require(reminderOffsets == reminderOffsets.sorted()) }`; yaml mirrors.
- [ ] **Step 3:** `send()` adds `.set(rr.SENT_AT, now)`.
- [ ] **Step 4:** `./gradlew generateJooq compileKotlin` OK; commit — `feat(backend): V9 lifecycle columns and workflows config`

---

### Task 2: workflows module scheduler infra

**Files:** Create `workflows/RecurringTask.kt` (public API per spec), `workflows/infrastructure/RecurringTaskRunner.kt`.

- [ ] **Step 1:** Runner: `@Component` with `TaskScheduler` bean (`ThreadPoolTaskScheduler`, poolSize 2, prefix `workflows-`); on `ApplicationReadyEvent`, if `props.workflows.enabled`, `scheduler.scheduleWithFixedDelay({ runSafely(task) }, task.interval)` per bean; `runSafely` try/catch + `log.error`. Compile; commit — `feat(backend): workflows module recurring task scheduler (ADR-0005 fallback)`

---

### Task 3: requests lifecycle task + stop-reminders

**Files:** Create `requests/application/ReferenceRequestLifecycleTask.kt`; Modify `RecommenderFlowService.kt` (stopReminders), `InvitationController.kt` (`POST /{token}/stop-reminders`), `ReferenceRequestService.kt` + `RecommenderFlowService.kt` (stop link in invitation/correction/code emails), Test `requests/ReferenceRequestLifecycleTaskTest.kt` (integration).

**Task logic (single class, two passes per run):**
- reminders: select active-status rows where `SENT_AT is not null and REMINDERS_STOPPED_AT is null and REMINDERS_SENT < offsets.size` and `SENT_AT + offsets[REMINDERS_SENT] <= now` (filter in Kotlin after fetching candidates by status — offsets indexing is per-row); per row (own transaction via TransactionTemplate or self-injected @Transactional helper — plan: separate `@Service LifecycleActions` with @Transactional methods `sendReminder(requestId)` / `expire(requestId)` so each row commits independently): revoke+mint token (TTL = max(until expires_at, 1d)), email (last offset ⇒ warning copy "This request expires soon"), REMINDERS_SENT+1, audit REMINDER_SENT.
- expiration: active-status rows with `EXPIRES_AT <= now` → CAS to EXPIRED, revoke tokens+sessions, audit REFERENCE_REQUEST_EXPIRED (SYSTEM).

**stopReminders(rawToken)**: identify (post-consumption OK), request non-terminal (409 otherwise is unnecessary — idempotent 200 even if already stopped; terminal → 200 no-op? keep decline-style: non-terminal check → set REMINDERS_STOPPED_AT=now if null + audit REMINDERS_STOPPED once; terminal request → 200 no-op since reminders can't fire anyway).

- [ ] **Step 1:** Implement task + actions + endpoint + email links.
- [ ] **Step 2: Integration tests** — backdate `SENT_AT` 4 days → run() → reminder email with fresh working invitation link (GET 200) and `/stop-reminders` link, REMINDERS_SENT=1, audit; immediate second run() sends nothing; backdate 8 days → second reminder; stop click → run() sends nothing + audit REMINDERS_STOPPED; submitted request gets none (status filter); backdate EXPIRES_AT → run() → EXPIRED + invitation GET 404 + token REVOKED + audit; COMPLETED untouched; mail.failFor ⇒ REMINDERS_SENT unchanged.
- [ ] **Step 3:** PASS; commit — `feat(backend): reference request reminders, auto-expiration, one-click stop`

---

### Task 4: files pending-upload cleanup

**Files:** Create `files/application/PendingUploadCleanupTask.kt`; Test in `requests/RecommenderFlowIntegrationTest.kt` or new `files/PendingUploadCleanupTest.kt`.

- [ ] **Step 1:** Task (interval = cleanupInterval): PENDING rows with `CREATED_AT < now - pendingUploadTtl` → best-effort staging delete, status DELETED + DELETED_AT, audit FILE_DELETED (SYSTEM, reason pending_ttl).
- [ ] **Step 2: Test** — create upload via API (PENDING), backdate CREATED_AT 2 days, run() → DELETED; fresh PENDING untouched. Commit — `feat(backend): pending upload TTL cleanup task`

---

### Task 5: documents expired-link signal sweep + verification.markExpired

**Files:** Modify `verification/VerificationSignals.kt` + impl (`markExpired(entityType, entityId, signalType): Int` — same shape as markRevoked, newStatus EXPIRED); Create `documents/application/ExpiredShareLinkSignalTask.kt`; Test in `verification/PublicVerificationIntegrationTest.kt`.

- [ ] **Step 1:** Implement (select expired unrevoked links joined to VERIFIED signals → markExpired per link).
- [ ] **Step 2: Test** — expired link (backdated) with VERIFIED signal → run() → signal EXPIRED + VERIFICATION_SIGNAL_UPDATED audit; revoked link's signal stays REVOKED. Commit — `feat(backend): expired share-link signal sweep`

---

### Task 6: docs + OpenAPI + full suite

- [ ] ADR-0005 implementation note; MODULES.md workflows section; AUDIT_EVENTS.md (+2 events); WORKFLOWS.md Reminder Policy implementation note; ROADMAP (minimal workflows delivered via documented fallback → MVP backend feature-complete); IMPLEMENTATION_HISTORY iteration 8. `UPDATE_OPENAPI=true` refresh; `./gradlew test --rerun-tasks -x generateJooq` all green + counts. Commit — `docs(backend): OpenAPI + docs for minimal workflows`

---

### Task 7: Push and PR

- [ ] `git push -u origin feature/minimal-workflows`; `gh pr create` with summary, spec/plan links, AGENTS.md checklist, risks (single-instance scheduler, draft grace-extension deferred, Temporal migration path).
