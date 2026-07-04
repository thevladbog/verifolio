# Workflows

## Workflow Engine

Verifolio uses Temporal for long-running business workflows.

Temporal workflows must orchestrate business processes but should not contain all domain logic. Domain logic belongs in application services.

## Reference Request Workflow

Purpose: manage a reference request from draft to completion.

```text
DRAFT
  -> SENT
  -> OPENED
  -> IN_PROGRESS
  -> SUBMITTED
  -> VERIFIED
```

Alternative terminal states:

```text
EXPIRED
REVOKED
REJECTED
```

Workflow responsibilities:

- send request email;
- track opening;
- send reminders;
- handle expiration;
- accept response;
- trigger document generation;
- trigger verification;
- create audit events.

## Document Verification Workflow

Purpose: verify document evidence after submission.

Steps:

1. Validate submitted response.
2. Generate document version.
3. Generate PDF.
4. Store PDF.
5. Calculate hash.
6. Attach scan if provided.
7. Attach signature if provided.
8. Create verification signals.
9. Lock document version.
10. Enable verification page if allowed.

## Signature Verification Workflow

Purpose: verify a signature attached to a document.

Steps:

1. Load original file metadata.
2. Load signature file metadata.
3. Validate file hashes.
4. Detect signature format.
5. Route to regional signature provider.
6. Extract certificate metadata.
7. Store verification result.
8. Create verification signal.
9. Create audit event.

## Profile Verification Workflow

Purpose: increase trust in a user profile.

Possible steps:

- email verification;
- phone verification;
- professional link verification;
- name consistency check;
- digital signature verification;
- external identity verification later.

## Share Link Expiration Workflow

Purpose: expire or revoke public/private verification links.

Rules:

- share links may expire;
- share links may be revoked;
- revoked links must stop access immediately;
- accesses must be audited where appropriate.

## Reminder Policy

Reference requests may have reminder schedules:

```text
Day 0: initial request
Day 3: first reminder
Day 7: second reminder
Day 14: expiration warning
Day 21: expire
```

Actual values must be configurable per template/region.

## Workflow Testing

Every workflow must have:

- happy path test;
- expiration test;
- revocation test;
- retry/failure test;
- idempotency test where appropriate.

## Idempotency

All workflow activities that create external effects must be idempotent.

Examples:

- sending email;
- generating PDF;
- uploading file;
- creating verification signal;
- locking document version.

## AI-Agent Rule

AI agents must not add new workflows without:

- workflow description;
- state diagram or transition list;
- tests;
- audit event definitions;
- failure handling notes.
