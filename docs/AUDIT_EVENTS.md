# Audit Events

## Purpose

Audit events are part of Verifolio's trust model.

They provide traceability for sensitive operations involving identity, documents, files, signatures, share links, and verification.

## Principles

- Audit events are append-only.
- Sensitive actions must create audit events.
- Audit logs must be region-local.
- Audit logs must minimize personal data.
- Audit events must not contain secrets or raw tokens.

## AuditEvent Model

```text
AuditEvent
- id
- actor_type
- actor_id
- action
- entity_type
- entity_id
- metadata_json
- ip_hash
- user_agent_hash
- created_at
```

## Actor Types

```text
USER
RECOMMENDER
PUBLIC_VIEWER
SYSTEM
ADMIN
WORKFLOW
```

## Entity Types

`entity_type` uses the canonical EntityType enum defined in `docs/DATA_MODEL.md` (USER_ACCOUNT, USER_PROFILE, ORGANIZATION, RECOMMENDER_CONTACT, REFERENCE_REQUEST, REFERENCE_RESPONSE, DOCUMENT, DOCUMENT_VERSION, FILE_OBJECT, SIGNATURE, VERIFICATION_SIGNAL, SHARE_LINK, SESSION, MAGIC_LINK_TOKEN, INVITATION_TOKEN, TEMPLATE, CONSENT_RECORD, DATA_SUBJECT_REQUEST).

This document does not maintain its own entity-type list. Do not add entity types here — extend the enum in `DATA_MODEL.md`.

## Definition of "Sensitive Action"

An action is sensitive if it creates, reads, or mutates any canonical entity type, or crosses an authorization boundary.

When in doubt, emit an event and document it.

## Required Events

### Authentication

```text
MAGIC_LINK_REQUESTED
MAGIC_LINK_CONSUMED
LOGIN_SUCCEEDED
LOGIN_FAILED
LOGOUT
SESSION_CREATED
SESSION_REVOKED
INVITATION_TOKEN_CONSUMED
INVITATION_TOKEN_REVOKED
```

### Reference Requests

```text
REFERENCE_REQUEST_CREATED
REFERENCE_REQUEST_SENT
REFERENCE_REQUEST_OPENED
REFERENCE_REQUEST_CANCELLED
REFERENCE_REQUEST_EXPIRED
REFERENCE_REQUEST_REMINDER_SENT
REMINDERS_STOPPED
REQUEST_DECLINED
REQUEST_CORRECTION_REQUESTED
```

`REFERENCE_REQUEST_REMINDER_SENT` (actor SYSTEM) records each reminder email of the
Reminder Policy schedule; `REMINDERS_STOPPED` (actor RECOMMENDER) records the one-click
stop-reminders action.

`REQUEST_DECLINED` metadata carries `reason` (`declined` / `abuse_report` /
`consent_declined`), `previousStatus`, and — only when the recommender chose one —
`reasonCategory` (`DONT_KNOW_REQUESTER | TOO_BUSY | NOT_COMFORTABLE | OTHER`; enum value
only, never free text).

Rename note: `REFERENCE_REQUEST_CANCELLED` replaces the former `REFERENCE_REQUEST_REVOKED` to align with the `CANCELLED` request status and the `/cancel` API command.

### Consent

```text
CONSENT_GRANTED
CONSENT_DECLINED
CONSENT_WITHDRAWN
```

`CONSENT_WITHDRAWN` (actor SYSTEM, entity `REFERENCE_REQUEST`) records the Flow-10 consent
withdrawal: the request's GRANTED recommender consent rows are flipped to `WITHDRAWN` (never
deleted — they evidence lawful basis). Metadata carries `requestId`, `recommenderContactId`
and the withdrawn-row `count` only.

### Data Subject Requests

```text
DATA_SUBJECT_REQUEST_RECEIVED
DATA_SUBJECT_REQUEST_APPROVED
DATA_SUBJECT_REQUEST_REJECTED
DATA_SUBJECT_REQUEST_EXECUTED
RECOMMENDER_PII_ERASED
ACCOUNT_DELETED
DATA_EXPORTED
FILE_DELETED
REGION_MIGRATION_STARTED
REGION_MIGRATION_COMPLETED
REGION_MIGRATION_FAILED
```

`RECOMMENDER_PII_ERASED` (actor SYSTEM, entity `REFERENCE_REQUEST`) records operational
erasure of a recommender's PII snapshot for one reference request (privacy erasure matrix:
nulls the request name/email snapshot, deletes reference responses, deletes unattached
uploads, nulls the invitation-token email, deletes recommender sessions and confirmation
codes). Metadata is IDs/counts only: `requestId`, `responsesDeleted`, `uploadsDeleted`,
`tokensScrubbed`, `sessionsDeleted`. Each physical upload delete additionally emits its own
`FILE_DELETED` (actor SYSTEM) from the files module.

`DATA_SUBJECT_REQUEST_RECEIVED` (actor USER for the account-holder channel, RECOMMENDER for the
account-less recommender channel) records intake; `DATA_SUBJECT_REQUEST_EXECUTED` (actor USER,
SYSTEM, or ADMIN when triggered from the admin console) records completion. `DATA_SUBJECT_REQUEST_APPROVED`
/ `_REJECTED` (actor ADMIN, `actorId` = admin account id) record admin review decisions. Metadata
carries `type`/`region`/`previousStatus` enums only. Hybrid
execution: a verified `CONSENT_WITHDRAWAL` runs RECEIVED → EXECUTED in one chain, emitting
`CONSENT_WITHDRAWN`, `VERIFICATION_SIGNAL_UPDATED` (per revoked signal), `RECOMMENDATION_RETRACTED`
and `RECOMMENDER_PII_ERASED` along the way.

`DATA_EXPORTED` (actor ADMIN when triggered from the admin console — `actorId` = admin account id —
else SYSTEM; entity `DATA_SUBJECT_REQUEST`) records completion of an `EXPORT` DSR: the metadata JSON
package was assembled, stored as a `DATA_EXPORT` `FILE_OBJECT` (its own `FILE_UPLOADED` from the files
module), and the presigned link emailed to the subject. Metadata is IDs/enums only — `fileId` and
`subjectType` (`ACCOUNT_HOLDER`|`RECOMMENDER`); never the subject email or any package content. The
export's `DATA_SUBJECT_REQUEST_EXECUTED` records the RECEIVED/APPROVED → EXECUTED transition. The
remaining DSR types (`REGION_MIGRATION`, `CORRECTION`, and account-holder `DELETION` until its
executor lands) stay RECEIVED for manual/admin execution in a later iteration.

### Admin

```text
ADMIN_ACCOUNT_CREATED
ADMIN_LOGIN_SUCCEEDED
ADMIN_LOGIN_FAILED
ADMIN_SESSION_CREATED
ADMIN_SESSION_REVOKED
ADMIN_DSR_VIEWED
```

`ADMIN_ACCOUNT_CREATED` (actor SYSTEM, entity `ADMIN_ACCOUNT`) records config-driven bootstrap of a
SUPERADMIN admin account. `ADMIN_LOGIN_SUCCEEDED` + `ADMIN_SESSION_CREATED` (actor ADMIN, entity
`ADMIN_ACCOUNT` / `ADMIN_SESSION`) are emitted together when the two-factor sequence (magic-link +
TOTP) mints an admin session; `ADMIN_SESSION_REVOKED` (actor ADMIN, entity `ADMIN_SESSION`) records
admin logout. Metadata is IDs/enums only (`region`, `adminId`); admin auth is isolated from user auth
(separate cookie/session/chain). `ADMIN_LOGIN_FAILED` is reserved for future failed-factor
recording; the always-202 magic-link request is anti-enumeration and is not itself audited per-email.

`ADMIN_DSR_VIEWED` (actor ADMIN, entity `DATA_SUBJECT_REQUEST`) records every admin read of the DSR
review queue — a list read carries the listed ids (`dsrIds`, comma-separated); a detail read carries
the single `dsrId`. Metadata is IDs only (`region`, `adminId`, `dsrId`/`dsrIds`); no subject data is
copied into the audit. Admin DSR decisions/executions reuse the existing DSR lifecycle events
(`DATA_SUBJECT_REQUEST_APPROVED` / `_REJECTED` / `_EXECUTED`) with the acting admin recorded as the
ADMIN actor (`actorId` = admin account id).

### Recommender Response

```text
RECOMMENDER_EMAIL_CONFIRMED
REFERENCE_RESPONSE_STARTED
REFERENCE_RESPONSE_SUBMITTED
REFERENCE_RESPONSE_ACCEPTED
RECIPIENT_CONFIRMED_BY_RECOMMENDER
RELATIONSHIP_CONFIRMED_BY_RECOMMENDER
```

`REFERENCE_RESPONSE_ACCEPTED` is emitted when the recipient accepts a submitted response in recipient review (`NEEDS_REVIEW` → `COMPLETED`); see the status transition table in `WORKFLOWS.md`.

### Documents

```text
DOCUMENT_CREATED
DOCUMENT_VERSION_CREATED
DOCUMENT_VERSION_LOCKED
DOCUMENT_VERSION_TOMBSTONED
DOCUMENT_PDF_GENERATED
SHARE_LINK_CREATED
SHARE_LINK_REVOKED
SHARE_LINK_EXPIRED
```

Rename note: `SHARE_LINK_CREATED` / `SHARE_LINK_REVOKED` replace the former `DOCUMENT_SHARED` / `DOCUMENT_SHARE_REVOKED` event names to align with the SHARE_LINK entity type.

`DOCUMENT_VERSION_TOMBSTONED` (actor SYSTEM, entity `DOCUMENT_VERSION`) records the single
sanctioned content-erasing mutation of a locked version: the generated PDF and attachment
objects are physically deleted (each emitting its own `FILE_DELETED`), then `content_json`
and `rendered_html` are nulled and the status flips to `TOMBSTONED`; `sha256_hash`,
`version_number` and `locked_at` are retained. Metadata is IDs only: `versionId`.

### Files

```text
FILE_UPLOAD_REQUESTED
FILE_UPLOADED
FILE_VALIDATED
FILE_DOWNLOAD_REQUESTED
FILE_DOWNLOAD_GRANTED
FILE_DOWNLOAD_DENIED
```

### Signatures

```text
SIGNATURE_ATTACHED
SIGNATURE_VERIFICATION_STARTED
SIGNATURE_VERIFICATION_SUCCEEDED
SIGNATURE_VERIFICATION_FAILED
```

### Verification

```text
VERIFICATION_SIGNAL_CREATED
VERIFICATION_SIGNAL_UPDATED
RECOMMENDATION_RETRACTED
PUBLIC_VERIFICATION_PAGE_VIEWED
PUBLIC_VERIFICATION_PAGE_DOWNLOAD
```

`RECOMMENDATION_RETRACTED` (actor SYSTEM, entity `REFERENCE_REQUEST`) records the recommender
retracting the recommendation: `retracted_at` is stamped on the request's document versions
(locked content is NOT modified — retraction ≠ deletion). Metadata is IDs/counts only:
`requestId`, `count`.

Public page view rules:

- page views are counted aggregated/sampled — `PUBLIC_VERIFICATION_PAGE_VIEWED` is not a full per-view audit row;
- full audit events are emitted only for downloads and state changes;
- `ip_hash`/`user_agent_hash` are keyed HMAC with a per-cell secret pepper (see `SECURITY.md`); keyed hashes remain personal data under GDPR.

### Profiles & Contacts

```text
PROFILE_CREATED
PROFILE_UPDATED
CONTACT_CREATED
CONTACT_UPDATED
CONTACT_DELETED
```

Contact audit metadata contains `relationshipType` only — name and email are never included. Template reads are not audited — templates contain no personal data and reads cross no authorization boundary.

### Region Policy

```text
REGION_SELECTED
REGION_POLICY_CHECK_FAILED
EXTERNAL_PROVIDER_BLOCKED_BY_REGION_POLICY
```

## Metadata Rules

Allowed metadata:

- entity IDs;
- safe status values;
- provider name;
- region;
- signal type;
- file purpose;
- hash prefix if needed.

Forbidden metadata:

- raw tokens;
- full private URLs;
- document text;
- file content;
- personal document numbers;
- passwords;
- raw signatures.

## Audit Log Access & Integrity

Rules:

- audit logs may be read only by per-region admins; every admin read of audit logs is itself audited;
- audit-log retention is bounded per region policy (`REGION_POLICIES.md`) — no indefinite retention;
- tamper-evidence is recommended: hash chaining of audit rows so deletions or edits are detectable;
- on data subject erasure, audit rows are pseudonymized (actor identifiers replaced) and retained only until the region's bounded audit-retention window expires (see `PRIVACY_AND_DATA_CLASSIFICATION.md`).

## AI-Agent Rule

Any new sensitive action must define an audit event.
