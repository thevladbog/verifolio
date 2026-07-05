# DSR Executors (EXPORT + Account-Holder DELETION) — Design

Date: 2026-07-05
Status: approved (user: export = JSON metadata + references via emailed presigned link;
execution = synchronous)
Scope: iteration 14 — implement the two highest-value DSR executors that today throw
`409 EXECUTION_NOT_AUTOMATED`: **EXPORT** (GDPR Art. 15/20 access + portability) and
**account-holder DELETION** (Art. 17 erasure). Both run synchronously inside the existing
`DataSubjectRequestService.execute(id, adminActorId)` (admin-triggered from the iteration-13
queue). REGION_MIGRATION stays stubbed (needs a second cell). CORRECTION stays stubbed
(it is a request-correction flow, not an erasure/export executor).

Normative sources: PRIVACY_AND_DATA_CLASSIFICATION.md (erasure/tombstoning §275-291),
USER_FLOWS.md Flow 11, DATA_MODEL.md, and the merged privacy/DSR core (iteration 11) +
admin queue (iteration 13). Reuses the erasure/tombstone/retraction ports already built.

## What already exists (reused, not rebuilt)

- `DataSubjectRequestService.execute(id, adminActorId)` — the sync entry point; today
  CONSENT_WITHDRAWAL + recommender-scoped DELETION run here, others 409.
- `documents.DocumentTombstone.tombstone(versionId)` — NULL content + TOMBSTONED, retains
  sha256/version/lockedAt. Reused for the subject's owned documents.
- `files.FileStore.{storeGeneratedPdf, presignedDownloadUrl, deleteGeneratedAsSystem}` —
  the store-bytes → presigned-GET pattern reused for the export artifact.
- `MailPort` — emails the subject the export link.
- The DSR admin queue + `execute` endpoint (iteration 13) — unchanged; the 409 for these
  two types simply becomes a successful EXECUTED once the executors exist.

## Design decisions on the unspecified items

1. **Export content — structured JSON of metadata + references (user decision).** The
   package is a single JSON document, NOT a bundle of raw file bytes. Files (generated
   PDFs, scans) are referenced by id + sha256 + download-in-app note, not embedded — keeps
   generation synchronous and small; the subject retains in-app access to their documents.
2. **Export delivery — one-time presigned link emailed to the subject.** Store the JSON as
   a `DATA_EXPORT` FileObject; mint a presigned GET (`verifolio.privacy.export-link-ttl`,
   default 7d); email the subject `${frontendBaseUrl}` is not used — the presigned URL is
   sent directly (it is a short-lived object-storage URL to the subject's OWN data, the one
   sanctioned case; it is not a public page and is not logged). `dsr.export_file_id` records
   the artifact for audit/re-fetch.
3. **Execution — synchronous (user decision).** Both executors run inside `execute()`;
   RECEIVED/APPROVED → EXECUTED in one call, mirroring the recommender-DELETION path. No new
   status, no job runner. Async orchestration remains the tracked scaling item (Temporal).
4. **Audit log in the export — deferred.** The export covers the subject's account, profile,
   contacts, requests, documents, and consents (the data they provided / that concerns
   them). The full audit trail is NOT included in v1 (its pseudonymization semantics are a
   separate concern); Art. 15 access to processing logs remains a manual admin path — noted.
5. **Account-holder terminal state — tombstone, not hard-delete.** `user_account.status =
   'DELETED'` + `deleted_at`, email anonymized; the row is retained (FK integrity for
   retained consent/audit) but carries no PII. Documents tombstoned (locked-version rules).
   Audit actor pseudonymized.

## Schema (Flyway V15)

```sql
-- Export artifact purpose.
alter table file_object drop constraint file_object_purpose_check;  -- name per V6; verify
alter table file_object add constraint file_object_purpose_check
  check (purpose in ('GENERATED_PDF','SCAN','DETACHED_SIGNATURE','CERTIFICATE',
                     'PREVIEW_IMAGE','ATTACHMENT','DATA_EXPORT'));

-- Account tombstone marker (status already free-text; add the timestamp).
alter table user_account add column deleted_at timestamptz;

-- Link the generated export to its DSR (audit + potential re-fetch).
alter table data_subject_request add column export_file_id uuid references file_object(id);
```

(The migration must read V6's actual constraint name; if the purpose check is inline/unnamed
it recreates it. Verify before writing.)

## Configuration

```yaml
verifolio:
  privacy:
    export-link-ttl: 7d      # presigned GET lifetime for the export artifact
```

## EXPORT executor (privacy)

`ExportExecutor.execute(dsr): FileRef` — synchronous, called from `execute()` for
`type == EXPORT`:

1. Resolve the subject: account-holder (`user_id`) or recommender (`recommender_contact_id`).
2. Assemble a JSON package (metadata + references only) via new read ports:

| Section | Source port (new) | Content (metadata only) |
|---|---|---|
| `account` | `identity.AccountExport.forUser(userId)` | email, region, status, createdAt |
| `profile` | `profiles.ProfileExport.forUser(userId)` | displayName, headline, legalName, locale |
| `contacts` | `contacts.ContactExport.forOwner(profileId)` | per contact: name, email, company, relationship, createdAt |
| `referenceRequests` | `requests.RequestExport.forRequester(profileId)` | per request: id, recommender snapshot, purpose, status, timestamps |
| `documents` | `documents.DocumentExport.forOwner(profileId)` | per document: type + versions [versionNumber, sha256, status, lockedAt, retractedAt, tombstonedAt] |
| `consents` | privacy (own `consent_record` where user_id) | consentType, status, policyTextVersion, timestamps |
| `dataSubjectRequests` | privacy (own DSR rows for the subject) | type, status, timestamps |

For a **recommender subject** (no account): a thinner package — `requests.RequestExport.
forRecommenderEmail(email)` (requests they were invited to + their response/consent metadata
that survives erasure). Sections absent for a recommender (account/profile/contacts) are
omitted.

Then: serialize (pretty JSON, top-level `{generatedAt, subjectType, ...sections}`), store
via `files.FileStore.storeExport(bytes)` (→ `DATA_EXPORT` FileObject, opaque region key);
`presignedDownloadUrl(fileId, ttl=export-link-ttl)`; `mail.send(subjectEmail, link)`; set
`dsr.export_file_id`; audit `DATA_EXPORTED` (actor ADMIN/SYSTEM, metadata: dsrId, fileId,
subjectType — no email/content); DSR → EXECUTED.

Module deps added: privacy → identity, profiles, contacts (all one-way; ModularityTests
must stay green). requests/documents export ports are new methods on existing ports or new
package-root ports.

## Account-holder DELETION executor (privacy)

`AccountDeletionExecutor.execute(dsr)` — synchronous, for `type == DELETION && user_id != null`
(recommender-scoped DELETION keeps its existing path). Erasure matrix:

| Data | Action | Port (new unless noted) |
|---|---|---|
| Owned `document_version`s (all versions of the subject's documents) | tombstone (NULL content, TOMBSTONED, retain sha256/version/lockedAt) | `documents.OwnerErasure.tombstoneForOwner(profileId)` → per-version `DocumentTombstone` (reused) |
| `person_profile` | anonymize PII (null displayName/headline/legalName; keep row for FK) | `profiles.ProfileErasure.eraseForUser(userId)` |
| `recommender_contact` owned by the subject | anonymize (null name/email/company; RESTRICT FKs from requests/consents hold) | `contacts.ContactErasure.eraseForOwner(profileId)` |
| `reference_request` the subject created (requester side) | leave the row (documents tombstoned above); requester PII lives in the profile, already anonymized | — |
| `user_account` | status='DELETED', `deleted_at`=now, email → `deleted-<id>@tombstone.invalid`; delete `user_session` + `magic_link_token` rows | `identity.AccountErasure.eraseForUser(userId)` |
| `consent_record` (subject = user) | RETAIN (lawful-basis evidence; FK to the tombstoned account holds) | — |
| `audit_event` referencing the subject as actor | pseudonymize `actor_id` → null | `audit.AuditPseudonymizer.pseudonymizeActor(userId)` |
| Recommender PII on the subject's requests | NOT this executor's job (that is the recommender-subject's own DSR); the requester deletion does not erase the recommenders who helped them | — |

Audit `ACCOUNT_DELETED` (actor ADMIN, metadata: dsrId, userId, counts — no email); DSR →
EXECUTED. Idempotent: re-running on an already-DELETED account is a no-op.

Non-negotiables held: locked versions only change via the sanctioned tombstone path; audit
rows are pseudonymized, never deleted (retention window is a separate deferred item); no
object-storage URL except the subject's own short-lived export link.

## Wiring `execute()`

In `DataSubjectRequestService.execute`, replace the 409 branches:
- `EXPORT` → `exportExecutor.execute(dsr)` then EXECUTED.
- `DELETION && user_id != null` → `accountDeletionExecutor.execute(dsr)` then EXECUTED.
- `DELETION && recommender_contact_id != null` → existing recommender path (unchanged).
- `REGION_MIGRATION`, `CORRECTION` → keep `409 EXECUTION_NOT_AUTOMATED`.

Admin flow unchanged: `POST /api/v1/admin/data-subject-requests/{id}/execute` (DSR_EXECUTE)
now succeeds for EXPORT + account-DELETION. The DSR detail may surface `exportFileId` /
an admin presigned re-download — optional, low priority (the subject already got the link).

## Frontend (minimal)

- The admin DSR queue "Execute" for EXPORT / account-DELETION now returns EXECUTED instead
  of the "manual execution required" (409) state — no new UI needed; the existing success
  path handles it. Copy on the detail may note "Export delivered to the subject" /
  "Account deleted" based on the executed type. No new screens.

## OpenAPI / docs

- No new endpoints; `execute` behavior changes (fewer 409s). If `exportFileId` is added to
  the admin DSR detail DTO, refresh the snapshot + regenerate the frontend client
  (`npm run gen:api`).
- Docs: PRIVACY_AND_DATA_CLASSIFICATION.md (export package contents + account-deletion
  matrix), AUDIT_EVENTS.md (DATA_EXPORTED, ACCOUNT_DELETED), DATA_MODEL.md (export_file_id,
  user_account.deleted_at, DATA_EXPORT purpose), API_GUIDELINES.md if a DTO field changes.

## Open questions (resolved; none block the plan)

1. **Recommender export completeness** — much recommender PII is erased on decline/withdrawal;
   the recommender export is intentionally thin (surviving response/consent metadata). Fine.
2. **Export link is a raw presigned URL emailed to the subject** — the one sanctioned
   object-storage URL exposure (the subject's own data, short-lived, not a public page, not
   logged). Consistent with FILES_AND_STORAGE rules (presigned, TTL-bounded).
3. **REGION_MIGRATION / CORRECTION** — remain 409; REGION_MIGRATION needs a second cell,
   CORRECTION is the existing request-correction flow. Noted deferred.
4. **Audit export** — deferred from the package v1 (separate pseudonymization concern); Art.
   15 processing-log access is a manual admin path for now.

## Non-negotiables check

- Locked versions: only the tombstone path NULLs content; retained hash/version/lockedAt.
- Object storage: only the subject's own short-lived presigned export link; all deletes via
  the files module; nothing logged.
- Domain authorization: executors run only via admin `execute()` (DSR_EXECUTE, region-scoped)
  — no new public surface.
- Consent + audit retained (never deleted); audit actor pseudonymized on account deletion.
- Region: executors operate in-cell on in-cell data; no cross-region flow.
- Flyway V15 only; V1–V14 untouched. Module boundaries stay one-way (privacy → owning
  modules); ModularityTests green.
