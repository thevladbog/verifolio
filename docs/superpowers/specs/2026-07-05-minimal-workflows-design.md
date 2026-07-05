# Minimal Workflows (Reminders & Expiration) — Design

Date: 2026-07-05
Status: approved
Scope: the last MVP roadmap item "Minimal workflows: reminders and expiration" —
`WORKFLOWS.md` Reminder Policy plus the accumulated cleanup follow-ups
(PENDING upload TTL, expired share-link signal sweep).

## Engine Decision

ADR-0005's documented MVP fallback is engaged: a **DB-backed scheduler** (Spring
TaskScheduler + due-timestamps in PostgreSQL) behind application-level interfaces in the
`workflows` module. No Temporal APIs anywhere; the later Temporal migration replaces the
scheduler infrastructure without touching domain logic. ADR-0005 gets an implementation
note recording this.

## workflows module (infrastructure only, per MODULES.md)

```kotlin
// package root — public API
interface RecurringTask {
    val name: String
    val interval: Duration
    /** One tick. Must be idempotent; exceptions are logged, never propagate to the scheduler. */
    fun run()
}
```

`RecurringTaskRunner` (workflows.infrastructure): collects all `RecurringTask` beans and
registers each with a Spring `TaskScheduler` at fixed delay = `task.interval`, wrapping
`run()` in try/catch + error logging. Enabled by `verifolio.workflows.enabled` (true;
**false in integration tests** — tests invoke tasks directly for determinism).
Dependency direction: domain modules → workflows (implement the interface); workflows
depends on no domain module.

## Data (Flyway V9)

`reference_request` gains:
- `sent_at timestamptz` — set by `send()`; the reminder schedule anchor (day 0);
- `reminders_sent int not null default 0`;
- `reminders_stopped_at timestamptz` — one-click stop, abuse report, or terminal states
  set it implicitly via status checks.

## Configuration

```yaml
verifolio:
  workflows:
    enabled: true
    tick-interval: 1m          # lifecycle task interval; cleanups run less often
    reminder-offsets: [3d, 7d, 14d]   # from sent_at; the last one is the expiration warning
    cleanup-interval: 1h
    pending-upload-ttl: 24h
```

`reminderOffsets` must be sorted ascending (validated at binding).

## Tasks

### `ReferenceRequestLifecycleTask` (requests module; interval = tick-interval)

Reminders — for every request in SENT/OPENED/IN_PROGRESS/CORRECTION_REQUESTED with
`sent_at` set, `reminders_stopped_at` null, `reminders_sent < offsets.size`, and
`now >= sent_at + offsets[reminders_sent]`:
1. revoke outstanding unconsumed invitation tokens, mint a fresh one (raw tokens are
   never stored, so links must be re-minted; TTL = remaining time to `expires_at`,
   min 1 day);
2. send the reminder email (invitation link + one-click stop-reminders + decline +
   report-abuse links); the final reminder (last offset) carries expiration-warning copy;
3. `reminders_sent += 1`; audit `REFERENCE_REQUEST_REMINDER_SENT` (actor SYSTEM,
   metadata: reminderNumber, of). Mail failure: the increment happens only after a
   successful send (same transaction; rollback retries next tick).

Expiration — requests in the four active statuses with `expires_at <= now`:
CAS transition → `EXPIRED`, revoke invitation tokens + recommender sessions, audit
`REFERENCE_REQUEST_EXPIRED` (actor SYSTEM). Expired requests stop resolving on the
invitation endpoints via the existing terminal-status checks.

Stop conditions (Reminder Policy): decline/submission stop reminders through the status
filter; the missing one-click endpoint is added —
`POST /api/v1/invitations/{token}/stop-reminders` (token identified even after
consumption, like decline; request must be non-terminal): sets `reminders_stopped_at`,
audits `REMINDERS_STOPPED` (actor RECOMMENDER, metadata reason=stop_link). Abuse report
already declines the request (terminal ⇒ reminders stop). All recommender emails
(invitation, correction, code, reminders) now include the stop-reminders link —
closing the gap with RECOMMENDER_EXPERIENCE.md ("every email").
Code-confirmation emails keep only code content (no links; they are step-2 of an
explicit user action) — documented interpretation: the "every recommender email" rule
applies to unsolicited emails (invitation/correction/reminders).

### `PendingUploadCleanupTask` (files module; interval = cleanup-interval)

FILE_OBJECT rows with status PENDING and `created_at < now - pendingUploadTtl`:
delete the staging object (best-effort), set status DELETED + `deleted_at`, audit
`FILE_DELETED` (actor SYSTEM, metadata reason=pending_ttl). Staging orphans without DB
rows stay out of scope (S3 lifecycle rule — deferred note).

### `ExpiredShareLinkSignalTask` (documents module; interval = cleanup-interval)

Share links with `expires_at <= now`, `revoked_at` null, and a VERIFIED
`PUBLIC_VERIFICATION_ENABLED` signal: flip the signal to **EXPIRED** (catalog semantics —
expiry is not revocation) via a new `VerificationSignals.markExpired(entityType,
entityId, signalType): Int` (audits `VERIFICATION_SIGNAL_UPDATED`, newStatus EXPIRED).
Closes the iteration-6 deferred item.

## Audit Catalog Additions (AUDIT_EVENTS.md updated)

- `REFERENCE_REQUEST_REMINDER_SENT` — reminder email sent (SYSTEM).
- `REMINDERS_STOPPED` — recommender used the one-click stop link (RECOMMENDER).

## Testing (deterministic — no sleeps, scheduler disabled in tests)

- Reminder due → email with a WORKING fresh invitation link (opened via API) + stop link;
  reminders_sent incremented; not re-sent on an immediate second run() (idempotency).
- Day-14 reminder carries the expiration warning copy.
- Stop-reminders click → no further reminders; audit REMINDERS_STOPPED; decline and
  submission also stop the schedule (status filter).
- Expiration: past-due request → EXPIRED, tokens/sessions revoked, invitation GET → 404,
  audit; terminal/completed requests untouched.
- Pending upload past TTL → DELETED + object gone; fresh PENDING untouched.
- Expired link with VERIFIED signal → signal EXPIRED (+audit); revoked links untouched
  (already REVOKED).
- Config validation: unsorted reminder offsets fail startup.
- Mail-failure path: send throws → reminders_sent unchanged (retried next tick).

## Documentation Updates

- ADR-0005: implementation note (fallback engaged for MVP; Temporal remains the target;
  domain logic already sits behind `RecurringTask`).
- MODULES.md: workflows section describes the scheduler infra ownership under the fallback.
- AUDIT_EVENTS.md: the two new events.
- WORKFLOWS.md: implementation note under Reminder Policy.
- ROADMAP.md: minimal workflows delivered (via documented fallback).
- IMPLEMENTATION_HISTORY.md: iteration 8.

## Risks / Accepted Trade-offs

- Single-instance scheduler (no distributed locking) — consistent with the in-process
  rate limiters; the Temporal migration or a DB lock arrives before multi-instance cells.
- Draft grace-extension near expiry (RECOMMENDER_EXPERIENCE.md) is NOT implemented — the
  day-14 warning covers the notification; extension logic is deferred and noted.
- Reminder timing resolution = tick interval (1m default) — adequate for day-granular
  offsets.
- Existing rows have `sent_at` null (pre-V9 sends) — they never receive reminders but
  still expire via `expires_at`; acceptable for dev data.
