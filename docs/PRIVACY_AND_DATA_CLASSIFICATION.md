# Privacy and Data Classification

## Purpose

This document classifies data handled by Verifolio and defines handling rules.

## Data Categories

### Public Data

Examples:

- marketing site content;
- product documentation;
- public brand assets.

Handling:

- can be global;
- can be cached/CDN-hosted.

### Account Data

Examples:

- email;
- phone (collected only if/when SMS features ship; see RU SMS provider policy in `REGION_POLICIES.md`);
- login events;
- session metadata;
- profile name.

Handling:

- region-local;
- minimized in logs;
- access controlled.

### Professional Profile Data

Examples:

- display name;
- work history fields;
- professional links;
- trust signals.

Handling:

- region-local;
- user-controlled visibility.

### Reference Data

Examples:

- recommender name;
- recommender email;
- relationship;
- answers;
- letter text.

Handling:

- region-local;
- sensitive;
- strict access control.

### Recommender PII

Recommender personal data is personal data of a data subject who may have no Verifolio account.

Examples:

- recommender name;
- recommender email;
- recommender email confirmation events;
- recommender consent records;
- recommender uploads.

Handling:

- region-local (stored in the cell chosen by the requester's region);
- processed only under an explicit recommender consent (see Consent Model);
- operational recommender PII is erasable on decline, withdrawal, or data subject request;
- consent records follow the retention rules in `Deletion & Erasure Model`;
- never published without RECOMMENDER_PUBLIC_SHARING_CONSENT.

### Public Viewer Telemetry

Examples:

- IP hash of anonymous verification page visitors;
- user-agent hash of anonymous visitors;
- aggregated/sampled page view counters.

Handling:

- hashes are keyed HMAC with a per-cell secret pepper (rotation defined in `SECURITY.md`);
- keyed hashes remain personal data under GDPR and are classified as personal data;
- page views are stored aggregated/sampled; full audit rows are recorded only for downloads and state changes;
- region-local;
- bounded retention per region policy.

### Document Files

Examples:

- generated PDF;
- uploaded scan;
- signature file;
- certificates.

Handling:

- private object storage;
- region-local;
- hash stored;
- no public object URLs.

### Verification Metadata

Examples:

- verification signal;
- timestamp;
- provider;
- evidence summary.

Handling:

- region-local;
- may be public only on authorized verification page.

### Audit Data

Examples:

- actor ID;
- action;
- entity ID;
- timestamp;
- IP hash;
- user-agent hash.

Handling:

- append-only;
- region-local;
- minimized.

### Highly Sensitive Data

Examples:

- identity verification documents;
- passport data;
- raw personal IDs;
- biometric/liveness data.

Handling:

- avoid storing in v1;
- use external regional providers if needed;
- store only verification result where possible;
- require separate security/privacy review.

## Logging Rules

Never log:

- raw tokens;
- passwords;
- full document text;
- file contents;
- private download URLs;
- raw identity documents.

## Data Minimization

Collect only what is required for the selected workflow.

## Consent Model

### Versioned Consent Texts

Each region policy (RU/EU/GLOBAL) defines versioned consent texts:

- a data-processing consent (152-FZ for RU, GDPR for EU);
- a cross-border transfer consent, used when the data subject and the storing cell are in different jurisdictions.

Every ConsentRecord references the exact consent text version accepted.

### Consent Types

```text
REQUESTER_VERBAL_CONSENT_ATTESTATION
RECOMMENDER_PROCESSING_CONSENT
RECOMMENDER_PUBLIC_SHARING_CONSENT
CROSS_BORDER_TRANSFER_CONSENT
```

### Requester Attestation

At request creation, the requester must check an attestation:

```text
I confirm the recommender gave me verbal consent to receive this request.
```

Rules:

- stored as a ConsentRecord of type REQUESTER_VERBAL_CONSENT_ATTESTATION;
- audited (CONSENT_GRANTED);
- invitations cannot be sent without it.

### Recommender Processing Consent

When the recommender opens the invitation (after email confirmation) and before entering any answers, they must either:

- explicitly ACCEPT the region's data-processing policy — stored as a ConsentRecord of type RECOMMENDER_PROCESSING_CONSENT with the versioned text; or
- explicitly DECLINE.

Decline effects:

- request status becomes DECLINED;
- reminders stop immediately;
- CONSENT_DECLINED audit event is emitted;
- recommender PII is scheduled for erasure.

### Public Sharing Consent

RECOMMENDER_PUBLIC_SHARING_CONSENT is a separate, optional consent covering:

- public verification pages;
- downloadable copies of the recommender's uploads.

Without it, the recommendation stays private to the recipient.

### Withdrawal

Any consent can be withdrawn later:

- CONSENT_WITHDRAWN audit event;
- triggers the retraction flow (verification signals set to REVOKED, public page marked retracted; see `WORKFLOWS.md`).

## Data Subject Requests

### Types

```text
DELETION
EXPORT
REGION_MIGRATION
CONSENT_WITHDRAWAL
CORRECTION
```

### Statuses

```text
RECEIVED -> IN_REVIEW -> APPROVED -> EXECUTED
                      -> REJECTED
```

### Rules

- Per-region SLA: GDPR — 30 days; 152-FZ — per statutory terms; defined in `REGION_POLICIES.md`.
- Available to account holders and to recommenders. Recommenders are data subjects without accounts: they submit requests via a verified email flow (confirmation code sent to the recommender email on file); no account is required.
- Every request and every status transition is audited (DATA_SUBJECT_REQUEST_RECEIVED / APPROVED / REJECTED / EXECUTED).
- Stored as DataSubjectRequest entities (see `DATA_MODEL.md`).

### EXPORT execution (GDPR Art. 15/20)

Executing an EXPORT DSR assembles a JSON package of the subject's **metadata and references only** —
it never contains reference-letter text, structured answers, or uploaded/rendered document bytes (the
subject retains in-app access to those; the package proves integrity via the version sha256 hashes).

- Account-holder package: `account` (email, region, status, createdAt), `profile` (displayName,
  legalName, preferredLocale), `contacts` (name, email, company, relationship, createdAt),
  `referenceRequests` (id, recommender snapshot, purpose, status, timestamps), `documents` (per
  document: type + versions [versionNumber, sha256, status, lockedAt, retractedAt, tombstonedAt]),
  `consents` (consentType, status, policyTextVersion, timestamps), `dataSubjectRequests` (type,
  status, timestamps). Retained consent records are included even after other data is erased.
- Recommender package (data subject without an account): intentionally thin — `referenceRequests`
  (matched on the surviving snapshot email), `consents`, and `dataSubjectRequests`; the
  account/profile/contacts/documents sections are omitted.
- The package is stored as a `DATA_EXPORT` `FileObject` under an opaque region-scoped key (never
  leaves its cell) and delivered as a **presigned GET link emailed to the subject**, valid for
  `verifolio.privacy.export-link-ttl` (default 7 days). This is the single sanctioned object-storage
  URL exposure: the subject's own data, short-lived, TTL-bounded, not a public page, and never
  logged. `data_subject_request.export_file_id` records the artifact for audit/re-fetch.
- Audited as `DATA_EXPORTED` (actor ADMIN/SYSTEM, entity DATA_SUBJECT_REQUEST, metadata `fileId` +
  `subjectType` only — no email or content), then DSR → EXECUTED.

## Deletion & Erasure Model

Rules:

- FileObjects are physically deleted or crypto-shredded (deletion of the per-object encryption key).
- Locked DocumentVersions are TOMBSTONED: content and files are deleted; the sha256 hash, version number, and lock date are retained.
- Audit rows are pseudonymized and retained only for the region's defined audit-retention window, then deleted.
- Public pages for tombstoned versions show: "content removed at data subject's request".

Legal basis for retained hashes: the sha256 hash, version number, and lock date are retained as integrity evidence under legitimate interest — they do not reveal content and cannot be reversed, but preserve the ability to detect forged copies of previously issued documents.

## Retention

Retention periods are defined per region and data type in `REGION_POLICIES.md`.

Rules:

- audit-log retention must be bounded (no indefinite audit storage);
- expired data is deleted per the Deletion & Erasure Model above;
- consent records are retained as long as needed to evidence the lawful basis, then per region policy.

## User Controls

Users should be able to:

- revoke share links;
- delete drafts;
- request export (Data Subject Request of type EXPORT);
- request deletion (Data Subject Request of type DELETION);
- withdraw consent (Data Subject Request of type CONSENT_WITHDRAWAL);
- control public visibility.

## AI-Agent Rule

Do not introduce new data collection without classifying the data.
