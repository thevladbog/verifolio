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
REQUEST_DECLINED
REQUEST_CORRECTION_REQUESTED
```

Rename note: `REFERENCE_REQUEST_CANCELLED` replaces the former `REFERENCE_REQUEST_REVOKED` to align with the `CANCELLED` request status and the `/cancel` API command.

### Consent

```text
CONSENT_GRANTED
CONSENT_DECLINED
CONSENT_WITHDRAWN
```

### Data Subject Requests

```text
DATA_SUBJECT_REQUEST_RECEIVED
DATA_SUBJECT_REQUEST_APPROVED
DATA_SUBJECT_REQUEST_REJECTED
DATA_SUBJECT_REQUEST_EXECUTED
ACCOUNT_DELETED
DATA_EXPORTED
FILE_DELETED
REGION_MIGRATION_STARTED
REGION_MIGRATION_COMPLETED
REGION_MIGRATION_FAILED
```

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
