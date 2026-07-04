# Workflows

## Workflow Engine

Verifolio uses Temporal for long-running business workflows.

Temporal workflows must orchestrate business processes but should not contain all domain logic. Domain logic belongs in application services.

## Reference Request Workflow

Purpose: manage a reference request from creation to completion.

Canonical happy path (see the canonical end-to-end sequence in `USER_FLOWS.md`):

```text
CREATED
  -> SENT
  -> OPENED
  -> IN_PROGRESS
  -> SUBMITTED
  -> NEEDS_REVIEW
  -> COMPLETED
```

Correction loop:

```text
NEEDS_REVIEW
  -> CORRECTION_REQUESTED
  -> IN_PROGRESS
```

Each new response cycle produces a new document version; locked versions are never edited.

Alternative terminal states:

```text
DECLINED   (recommender explicit decline)
EXPIRED    (day 21)
CANCELLED  (requester)
```

Note: the former `VERIFIED` state is renamed `COMPLETED`.

### Status Transition Table

| From | To | Actor | Trigger | Audit event |
|---|---|---|---|---|
| CREATED | SENT | System | Invitation email sent (requires `REQUESTER_VERBAL_CONSENT_ATTESTATION`) | REFERENCE_REQUEST_SENT |
| SENT | OPENED | Recommender | Invitation link opened | REFERENCE_REQUEST_OPENED |
| OPENED | IN_PROGRESS | Recommender | Email confirmed and processing consent accepted (`RECOMMENDER_PROCESSING_CONSENT`) | RECOMMENDER_EMAIL_CONFIRMED, CONSENT_GRANTED |
| OPENED | DECLINED | Recommender | Processing consent explicitly declined | CONSENT_DECLINED, REQUEST_DECLINED |
| IN_PROGRESS | SUBMITTED | Recommender | Response submitted with approved letter text | REFERENCE_RESPONSE_SUBMITTED |
| SUBMITTED | NEEDS_REVIEW | System | Response enters the recipient review queue (automatic) | — (covered by REFERENCE_RESPONSE_SUBMITTED) |
| NEEDS_REVIEW | COMPLETED | Recipient | Recipient accepts the response | REFERENCE_RESPONSE_ACCEPTED, DOCUMENT_VERSION_LOCKED, VERIFICATION_SIGNAL_CREATED |
| NEEDS_REVIEW | CORRECTION_REQUESTED | Recipient | Recipient requests a correction | REQUEST_CORRECTION_REQUESTED |
| CORRECTION_REQUESTED | IN_PROGRESS | Recommender | New response cycle starts (new document version on next acceptance) | REFERENCE_RESPONSE_STARTED |
| SENT / OPENED / IN_PROGRESS | DECLINED | Recommender | Explicit decline or abuse report | REQUEST_DECLINED |
| SENT / OPENED / IN_PROGRESS / CORRECTION_REQUESTED | EXPIRED | System | Day 21 without submission | REFERENCE_REQUEST_EXPIRED |
| Any non-terminal | CANCELLED | Requester | Requester cancels the request | REFERENCE_REQUEST_CANCELLED |

All audit event names above are defined in `docs/AUDIT_EVENTS.md` (the canonical event catalog).

Workflow responsibilities:

- record the requester's verbal-consent attestation before sending;
- send request email (with stop-reminders and report-abuse links);
- track opening;
- gate on recommender email confirmation and processing consent;
- send reminders (see Reminder Policy);
- handle expiration;
- accept response;
- route to recipient review;
- trigger document generation only after recipient acceptance;
- trigger verification;
- create mandatory audit events (see Mandatory Audit Events).

## Document Verification Workflow

Purpose: generate and verify the document after the recipient accepts the submission in `NEEDS_REVIEW`.

PDF generation, hashing, version locking, and signal creation happen only after recipient acceptance — never directly on submission.

Steps:

1. Validate the submitted response.
2. Confirm recipient acceptance (`NEEDS_REVIEW` → accepted).
3. Generate document version content. Rendering uses exactly the letter text the recommender approved before submission; no post-submission content changes. Both structured answers and the approved letter text are stored. If the document is translated, the original-language answers are canonical and translations are marked as such.
4. Generate PDF.
5. Store PDF.
6. Calculate hash.
7. Attach scan if provided.
8. Attach signature if provided (signatures cover the uploaded scan, not the generated PDF).
9. Create verification signals.
10. Lock document version.
11. Mark the document eligible for sharing. Public verification page enablement is an explicit recipient action (share link creation), never automatic.

If the recipient requests a correction instead, the workflow returns the request to a new response cycle; the next accepted submission produces a new document version, and prior locked versions remain visible as superseded.

## Signature Verification Workflow

Purpose: verify a signature attached to a document.

A signature covers a specific uploaded `FileObject` (the scan), never the generated PDF. Verification is bound to the hash of that target `FileObject`, and the public page must state which artifact the signature covers ("Signature verified for attached scan").

Steps:

1. Load target `FileObject` metadata (the uploaded scan the signature covers).
2. Load signature file metadata.
3. Validate the target `FileObject` hash and bind the verification result to it.
4. Detect signature format.
5. Route to regional signature provider.
6. Extract certificate metadata.
7. Store verification result referencing the target `FileObject` hash.
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

## Retraction and Consent Withdrawal Workflow

Purpose: honor a recommender-initiated retraction or consent withdrawal.

Steps:

1. Verify the recommender's identity via verified email (no account required).
2. Move all related verification signals to `REVOKED`.
3. Mark the public page: "Recommendation retracted by recommender on <date>".
4. Mark affected locked versions as superseded/retracted; locked versions are never edited.
5. On consent withdrawal, stop any further processing and schedule PII handling per DSR rules.
6. Create `RECOMMENDATION_RETRACTED` and consent-withdrawn audit events.

## Data Subject Request (DSR) Workflow

Purpose: execute data subject requests for account holders and account-less recommenders (via verified email).

Request types: `DELETION`, `EXPORT`, `REGION_MIGRATION`, `CONSENT_WITHDRAWAL`, `CORRECTION`.

Steps:

1. Verify the data subject's identity (session or verified email).
2. Execute the request:
   - `DELETION`: erase PII; tombstone affected locked versions so public pages show "content removed at data subject's request"; locked versions are never edited.
   - `EXPORT`: assemble and deliver an export package.
   - `REGION_MIGRATION`: export from source region, import into target region, apply source-region deletion/retention policy.
   - `CONSENT_WITHDRAWAL`: run the Retraction and Consent Withdrawal Workflow.
   - `CORRECTION`: start a new response cycle producing a new document version; old versions remain visible as superseded.
3. Audit every step; the full audit trail is preserved.

## Share Link Expiration Workflow

Purpose: expire or revoke share links.

Rules:

- the public verification page is reachable only via recipient-created tokenized share links pinned to a document version (`ShareLink.document_version_id`);
- share links may expire;
- share links may be revoked;
- revoked links must stop access immediately;
- link creation, revocation, and expiration must be audited;
- accesses (page views, downloads) must be audited (views aggregated/sampled).

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

Stop conditions — the reminder schedule stops immediately when any of the following occurs:

- recommender declines the request;
- recommender submits a response;
- recommender uses the one-click stop-reminders link;
- recommender reports abuse.

Every recommender email must include a one-click stop-reminders link and a report-abuse link.

A global per-recommender-email rate limit applies across all requesters to prevent spam.

## Mandatory Audit Events

Audit events for these surfaces are mandatory, not "where appropriate":

- public page view (aggregated/sampled);
- file download;
- share link create/revoke/expire;
- response submission;
- recipient accept / correction request;
- document version lock;
- verification signal create/revoke;
- consent granted/declined/withdrawn;
- request decline;
- recommendation retraction.

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
