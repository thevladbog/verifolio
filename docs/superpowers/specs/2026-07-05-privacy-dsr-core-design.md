# Privacy / DSR Core — Design

Date: 2026-07-05
Status: approved (user decisions: scope = core without export; execution = hybrid)
Scope: iteration 11 — first real code in the `privacy` module: DSR intake + lifecycle
(all five types accepted), recommender PII erasure on decline, retraction / consent
withdrawal (Flow 10), tombstoning of locked versions, and the public-page/frontend
states for both. Deferred to iteration 12+: EXPORT executor, account-holder DELETION
executor, REGION_MIGRATION execution, admin review UI.

Normative sources: PRIVACY_AND_DATA_CLASSIFICATION.md (erasure model §275-291, DSR
§246-270), USER_FLOWS.md Flows 9–11, DATA_MODEL.md (DataSubjectRequest §532-569,
DocumentVersion tombstoning), WORKFLOWS.md (retraction workflow §131-142),
PUBLIC_VERIFICATION_PAGE.md §74-76, AUDIT_EVENTS.md §105-117.

## Execution model (user decision: hybrid)

- **CONSENT_WITHDRAWAL executes automatically** immediately after subject verification
  (GDPR Art. 7(3): withdrawing must be as easy as granting). RECEIVED→EXECUTED in one
  transition chain, fully audited.
- **DELETION / EXPORT / REGION_MIGRATION / CORRECTION stay RECEIVED** for manual
  execution (roadmap explicitly allows manual execution until automation ships).
  `DataSubjectRequestService.execute(id)` is implemented and integration-tested for
  DELETION-of-recommender-subject (reuses the erasure service) and documented as the
  ops entry point; **no HTTP endpoint exposes execution** until the admin module.
  CORRECTION intake links to the existing correction flow in resolution notes.

## Schema (Flyway V11)

```sql
create table data_subject_request (       -- per DATA_MODEL.md §532-569
  id uuid primary key,
  type text not null check (type in ('DELETION','EXPORT','REGION_MIGRATION',
                                     'CONSENT_WITHDRAWAL','CORRECTION')),
  status text not null check (status in ('RECEIVED','IN_REVIEW','APPROVED',
                                         'EXECUTED','REJECTED')),
  region text not null,
  subject_email text not null,
  user_id uuid references user_account(id),
  recommender_contact_id uuid references recommender_contact(id),
  reference_request_id uuid references reference_request(id),  -- scope of a recommender DSR
  verified_at timestamptz,
  due_at timestamptz not null,            -- created_at + region SLA
  resolution_notes text,
  created_at timestamptz not null default transaction_timestamp(),
  updated_at timestamptz not null default transaction_timestamp(),
  constraint dsr_subject check (num_nonnulls(user_id, recommender_contact_id) = 1)
);

create table dsr_verification_code (      -- account-less recommender channel
  id uuid primary key,
  dsr_id uuid not null references data_subject_request(id),
  code_hash text not null,                -- HMAC via TokenHasher, 6 digits
  expires_at timestamptz not null,        -- 10 min
  attempts int not null default 0,        -- max 5
  consumed_at timestamptz,
  created_at timestamptz not null default transaction_timestamp()
);

alter table reference_request add column recommender_pii_erased_at timestamptz;
alter table reference_request alter column recommender_name drop not null;
alter table reference_request alter column recommender_email drop not null;
alter table document_version add column retracted_at timestamptz;
```

Note on the two `drop not null`s: erasure nulls the snapshot columns; all read paths
already tolerate terminal-state requests (owner UI shows the terminal banner) and get
explicit null-handling where needed.

## Recommender PII erasure

### Erasure matrix (what erasure of a request's recommender PII means)

| Data | Action |
|---|---|
| `reference_request.recommender_name/email` | NULL + set `recommender_pii_erased_at` |
| `reference_response` rows (drafts AND submitted) for the request | delete rows (letter/answers are recommender-authored operational data; on DECLINED nothing was published; on retraction the published artifact is governed separately by tombstone/retraction rules) |
| `response_upload` rows + their `file_object`s (not attached to a locked version) | physical S3 delete via files module → status DELETED, FILE_DELETED audit |
| `invitation_token.recommender_email` for the request | NULL (tokens already revoked in terminal states) |
| `recommender_session` rows for the request | delete rows (already revoked; row itself carries email) |
| `email_confirmation_code` rows for the request's tokens | delete rows |
| `recommender_contact` | NOT touched — it is the requester's address-book entry with its own lifecycle (deletable by the owner; RESTRICT FK is bypassed because the request snapshot is now null — FK unchanged, contact deletion rules unchanged) |
| `consent_record` | retained per PRIVACY doc (evidences lawful basis; attribution via contact id, not raw PII) |
| `document_attachment` / locked `document_version` | governed by retraction/tombstone rules, NOT by operational erasure |
| `audit_event` | untouched (already hash-only; retention window is a separate deferred item) |

Owner-visible effect: request card/detail show "Recommender details removed" placeholder
(i18n) when `recommenderPiiErasedAt` is set and the snapshot fields are null.

### Trigger: scheduled erasure on decline (Flow 9 debt, iteration 4)

`RecommenderPiiErasureTask` (privacy module, `RecurringTask`, interval =
`verifolio.workflows.cleanup-interval`): erases requests in DECLINED status with
`recommender_pii_erased_at IS NULL` and terminal transition older than
`verifolio.privacy.decline-erasure-grace` (default **24h** — keeps an
abuse-investigation window; "scheduled for erasure" per Flow 9). Per-row transactions;
S3 deletes outside DB transactions (files-module precedent); idempotent.

Module direction: privacy → requests/files public APIs. New public API
`requests.RecommenderPiiErasure.eraseForRequest(requestId): ErasureSummary` owns the
requests-side matrix rows; files deletions go through a new
`files.FileUploads.deleteUploadAsSystem(fileId)` overload (actor SYSTEM in the audit).
Audit: `RECOMMENDER_PII_ERASED` (SYSTEM, metadata: requestId, counts only) — added to
AUDIT_EVENTS.md.

## Retraction / consent withdrawal (Flow 10, auto-executed)

Verified CONSENT_WITHDRAWAL (recommender subject, scoped to a reference request)
executes:

1. `consent_record` rows for the request with subject = this recommender contact and
   status GRANTED → `WITHDRAWN` + `withdrawn_at` (public-sharing consents included —
   publicpages already gates attachment downloads on GRANTED, closing the iteration-7
   deferred item automatically).
2. `verification.VerificationSignals.markRevoked` for the request's document signals
   (existing API; audits VERIFICATION_SIGNAL_UPDATED per row).
3. `documents`: new public API `DocumentRetraction.markRetracted(requestId)` — sets
   `retracted_at` on the request's document versions (locked content NOT modified —
   non-negotiable), audits `RECOMMENDATION_RETRACTED`.
4. Operational PII erasure via the same `RecommenderPiiErasure` service (immediate, no
   grace — the subject explicitly asked).
5. DSR row → EXECUTED; audits DATA_SUBJECT_REQUEST_RECEIVED → ..._EXECUTED.

Public page for a retracted version: banner "Recommendation retracted by the
recommender on <date>" (from `retracted_at`), signals rendered in their REVOKED state,
downloads for attachments disabled by the withdrawn consents, generated PDF stays
downloadable (the document itself is not erased by retraction — that is DELETION).

## Tombstoning (DELETION execution path)

`documents.DocumentTombstone.tombstone(versionId)`:
- NULL `content_json`/`rendered_html`, status `TOMBSTONED`, `tombstoned_at` set —
  sha256, version number, lock date retained (PRIVACY §275-281);
- physically delete the version's PDF `file_object` and attachment files via the files
  module (S3 delete first, DB status after — cleanup-task precedent);
- audit `DOCUMENT_VERSION_TOMBSTONED`.

Wired into `DataSubjectRequestService.execute(id)` for DELETION with a recommender
subject scoped to a request (tombstone the request's versions + full PII erasure).
Account-holder DELETION (whole account) is iteration 12. Public page for a tombstoned
version: neutral "content removed at the data subject's request" state — header +
notice only; no signals, no downloads, no recommender/recipient blocks.

## DSR intake

### Channels

- **Account holder** (session): `POST /api/v1/privacy/data-subject-requests`
  `{type, comment?}` → RECEIVED (verified_at = now, subject = user). Own list:
  `GET /api/v1/privacy/data-subject-requests` (keyset cursor). No step-up in this
  iteration (deferred with the step-up item; session + audit is the MVP bar — noted
  in SECURITY.md follow-ups).
- **Account-less recommender** (public, CSRF-exempt like invitations):
  1. `POST /api/v1/privacy/recommender-requests` `{email, referenceRequestEmailHint?}`
     → always 202 (anti-enumeration): if the email matches any
     `reference_request.recommender_email` or `recommender_contact.email` in this cell,
     create RECEIVED DSR rows scoped per matching request? — **No**: create ONE DSR row
     (unverified, subject resolved to the newest matching contact) + 6-digit code
     emailed (`dsr_verification_code`, TokenHasher HMAC, TTL 10 min, 5 attempts,
     3/15 min resend limiter — email_confirmation_code precedents).
  2. `POST /api/v1/privacy/recommender-requests/{id}/verify` `{code, type,
     referenceRequestId?}` → sets `verified_at`, records the requested type; for
     CONSENT_WITHDRAWAL executes immediately (flow above) across all of the subject's
     reference requests in this cell, or the one in `referenceRequestId` if given.
     `CODE_INVALID` (400) on mismatch, attempts++ REQUIRES_NEW.
- Rate limits: per-email 3/15 min (codes), per-IP 100/15 min — `SlidingWindowRateLimiter`.

### Lifecycle & SLA

`due_at = created_at + verifolio.privacy.sla` (config per cell; local default **30d**
= GDPR; RU cell will set its statutory value with the region-policy work). Status
transitions audited: DATA_SUBJECT_REQUEST_RECEIVED / APPROVED / REJECTED / EXECUTED
(AUDIT_EVENTS.md catalog, already defined). No reminder/SLA-breach sweep in this
iteration (workflows follow-up).

## Frontend (same PR, minimal)

- `/verify/[token]`: retracted banner state + tombstoned "content removed" state
  (SSR — data from the public-page response, which gains `retractedAt` and renders the
  tombstoned shape).
- `/profile` gains a "Data & privacy" section (design 8c subset): submit a DSR
  (type select DELETION/EXPORT/CORRECTION + note that consent withdrawal for
  recommenders happens via the email channel), list own DSRs with status/due date.
- New public page `/data-requests` (linked from the public-page privacy notice and
  the respond flow footer): recommender email → 202 → code + type form →
  confirmation state ("consent withdrawal executed" / "request received, we will
  respond by <due date>").

## Open questions (resolved with recommendations, not blocking)

1. **Erasure of submitted responses on plain decline** — decline happens before
   submission in the state machine (DECLINED is reachable only from pre-SUBMITTED
   states), so the matrix's "submitted responses" row fires only via retraction/DSR
   paths. No conflict.
2. **Multiple matching contacts for a recommender email** — the verified channel
   resolves the subject by email match across the cell; execution applies per
   reference-request scope, so contact multiplicity is harmless (consents are keyed by
   contact per request).
3. **`REQUEST_DECLINED` abuse reports** — abuse_report declines follow the same 24h
   erasure; the abuse investigation window equals the grace period. Config raise is a
   one-line change.

## Non-negotiables check

- Locked versions: retraction only sets `retracted_at`; tombstoning nulls content via
  the single sanctioned path — no other mutation of locked versions anywhere.
- Object storage: all deletions via the files module; no URLs exposed.
- Domain authorization: owner endpoints session-scoped; recommender channel verified
  by emailed code; anti-enumeration 202 on the public entry.
- Consent: withdrawal recorded on the existing consent rows (status WITHDRAWN), never
  deleted; DSRs never blocked (intake accepts all five types from both channels).
- Every sensitive action audited: DSR lifecycle, erasure, retraction, tombstoning —
  all with ID/count-only metadata.
- Flyway V11 only; V1–V10 untouched. OpenAPI + docs updated with the change.
- Regional: DSR rows live in-cell; no cross-region flows (REGION_MIGRATION intake only).
