# Core Domain Model

## Implementation Status

The auth-related tables `user_account`, `magic_link_token`, `user_session`, and `audit_event` are implemented in `apps/backend` (Flyway V1 migration). The `person_profile`, `organization` (minimal), `recommender_contact`, and `template` tables are implemented in `apps/backend` (Flyway V2 migration); six English-locale templates are seeded via Flyway V3 data migration. The `reference_request`, `consent_record`, and `invitation_token` tables are implemented in `apps/backend` (Flyway V4 migration) — requester side only; recommender-side flows are not implemented yet. All other entities described in this document are specification-only so far and have no corresponding migrations.

## Canonical Enums

### EntityType

Canonical entity type enum, referenced by audit events and verification signals.

```text
USER_ACCOUNT
USER_PROFILE
ORGANIZATION
RECOMMENDER_CONTACT
REFERENCE_REQUEST
REFERENCE_RESPONSE
DOCUMENT
DOCUMENT_VERSION
FILE_OBJECT
SIGNATURE
VERIFICATION_SIGNAL
SHARE_LINK
SESSION
MAGIC_LINK_TOKEN
INVITATION_TOKEN
TEMPLATE
CONSENT_RECORD
DATA_SUBJECT_REQUEST
```

`AUDIT_EVENTS.md` and `VERIFICATION_SIGNALS.md` must use this enum for `entity_type` values.

## UserAccount

Represents an authenticated account in a specific regional deployment.

```text
UserAccount
- id
- region
- primary_email
- status
- created_at
- updated_at
```

`region` is denormalized for defense-in-depth and export/migration; isolation is physical per ADR-0003, never enforced by row filtering.

## PersonProfile

Represents the professional identity that owns documents and references.

```text
PersonProfile
- id
- user_account_id
- display_name
- legal_name
- preferred_locale
- profile_verification_status
- created_at
- updated_at
```

`UserAccount` is not the same as `PersonProfile`.

A user logs into an account. A profile is the professional holder of documents.

## AuthIdentity

Allows future auth providers.

```text
AuthIdentity
- id
- user_account_id
- provider
- external_subject
- email
- email_verified
- created_at
```

Providers may include:

```text
LOCAL
GOOGLE
GITHUB
LINKEDIN
OIDC
ENTERPRISE_SSO
```

Auth session entities (`Session`, `MagicLinkToken`, `InvitationToken`) are defined in `AUTHENTICATION.md`. `InvitationToken` carries an optional `recommender_contact_id` linking the invitation to the contact record.

## Organization

Represents a company or institution referenced by recommenders.

```text
Organization
- id
- name
- domains
- verification_status
- created_at
- updated_at
```

## RecommenderContact

Represents a person who can provide references.

```text
RecommenderContact
- id
- owner_profile_id
- organization_id (optional FK to Organization)
- name
- email
- company_name
- company_domain
- title
- relationship_type
- created_at
- updated_at
```

`relationship_type` enum values:

```text
MANAGER
COLLEAGUE
DIRECT_REPORT
CLIENT
PROFESSOR
MENTOR
PERSONAL
OTHER
```

## ReferenceRequest

Represents a request sent to a recommender.

```text
ReferenceRequest
- id
- requester_profile_id
- recommender_contact_id
- recommender_name
- recommender_email
- template_id
- purpose
- status
- expires_at
- created_at
- updated_at
```

`recommender_name` and `recommender_email` are snapshots of the contact taken at request
creation. The requester's verbal-consent attestation covers exactly this recipient, so the
invitation is always sent to the snapshotted address — editing the contact after creation
must not redirect an already-attested request. A contact referenced by reference requests
cannot be hard-deleted (`ON DELETE RESTRICT`; the API returns 409 `CONTACT_IN_USE`).

Statuses (canonical state machine):

```text
CREATED
SENT
OPENED
IN_PROGRESS
SUBMITTED
NEEDS_REVIEW
CORRECTION_REQUESTED
COMPLETED
DECLINED
EXPIRED
CANCELLED
```

Transitions:

```text
CREATED → SENT → OPENED → IN_PROGRESS → SUBMITTED → NEEDS_REVIEW → COMPLETED
NEEDS_REVIEW → CORRECTION_REQUESTED → IN_PROGRESS (a new response creates a new document version)
```

Terminal alternates:

- `DECLINED` — recommender explicitly declined;
- `EXPIRED` — request expired without submission;
- `CANCELLED` — requester cancelled the request.

`COMPLETED` replaces the former `VERIFIED` status. PDF generation, hashing, version locking, and signal creation happen only after the recipient accepts the response in `NEEDS_REVIEW`. Enabling the public page is an explicit recipient action, never automatic.

## Template

Represents a structured request type.

```text
Template
- id
- type
- locale
- name
- description
- question_schema_json
- output_schema_json
- required_fields_json
- verification_recommendations_json
- created_at
- updated_at
```

Template types:

```text
EMPLOYMENT_REFERENCE
IMMIGRATION_REFERENCE
VISA_SUPPORT_LETTER
ACADEMIC_RECOMMENDATION
CLIENT_TESTIMONIAL
CHARACTER_REFERENCE
CUSTOM
```

## ReferenceResponse

Represents the recommender's structured response.

```text
ReferenceResponse
- id
- request_id
- recommender_email
- answers_json
- submitted_at
- confirmation_text
- relationship_confirmed
- recipient_confirmed
```

`relationship_confirmed` and `recipient_confirmed` are the raw submission record. Verification signals derived from them are authoritative for display.

## Document

Represents a professional proof document.

```text
Document
- id
- owner_profile_id
- request_id (nullable)
- type
- status
- current_version_id
- created_at
- updated_at
```

`request_id` is explicitly nullable to support direct-upload proof documents that are not produced by a reference request.

Document types:

```text
REFERENCE_LETTER
EMPLOYMENT_PROOF
IMMIGRATION_REFERENCE
VISA_SUPPORT_LETTER
ACADEMIC_RECOMMENDATION
CLIENT_TESTIMONIAL
CHARACTER_REFERENCE
CUSTOM
```

`EMPLOYMENT_PROOF` has no request flow in MVP; it is a post-MVP direct-upload document type (created with `request_id = null`).

## DocumentVersion

Represents an immutable version of a document.

```text
DocumentVersion
- id
- document_id
- version_number
- content_json
- rendered_html
- pdf_file_id
- sha256_hash
- status
- locked_at
- locked_by_actor_id
- tombstoned_at
- created_at
```

Statuses:

```text
LOCKED
TOMBSTONED
```

Domain rule:

```text
Once locked_at is set, the version must never be modified.
```

Any change creates a new version.

Tombstoning (erasure): when a data subject deletion is executed, a locked version is tombstoned — `content_json`, `rendered_html`, and linked files are removed, while `sha256_hash`, `version_number`, and the lock date are retained. Tombstoning is the only permitted change to a locked version.

`rendered_html` is generated via a sanitized rendering pipeline and served with a strict Content-Security-Policy; previews should prefer the generated PDF.

Hash semantics: `DocumentVersion.sha256_hash` is the hash of the canonical `content_json`; `FileObject.sha256_hash` is the hash of the stored bytes. Signature verification always uses `FileObject` hashes — a signature covers a specific uploaded file, never the generated PDF.

## FileObject

Represents a stored file.

```text
FileObject
- id
- bucket
- storage_key
- original_filename
- mime_type
- size_bytes
- sha256_hash
- purpose
- status
- uploaded_by_actor_id
- deleted_at
- created_at
```

Statuses:

```text
PENDING
VALIDATING
READY
REJECTED
DELETED
```

Objects that remain `PENDING` beyond a TTL are cleaned up by a background job. Erasure requires physical deletion or crypto-shredding of stored bytes (`status = DELETED`, `deleted_at` set).

File purposes:

```text
GENERATED_PDF
SCAN
DETACHED_SIGNATURE
CERTIFICATE
PREVIEW_IMAGE
ATTACHMENT
```

## DocumentAttachment

Links files to document versions.

```text
DocumentAttachment
- id
- document_version_id
- file_object_id
- type
- created_at
```

## VerificationSignal

Represents one trust signal.

```text
VerificationSignal
- id
- entity_type
- entity_id
- signal_type
- status
- evidence_json
- provider
- verified_at
- expires_at
- created_at
```

`entity_type` uses the canonical `EntityType` enum defined in this document.

Signal types (see `VERIFICATION_SIGNALS.md` — the authoritative signal catalog):

```text
EMAIL_CONFIRMED
CORPORATE_DOMAIN_CONFIRMED
PHONE_CONFIRMED
IDENTITY_VERIFIED
NAME_MATCH
RECOMMENDER_RELATIONSHIP_CONFIRMED
RECIPIENT_CONFIRMED
SCAN_ATTACHED
SIGNATURE_ATTACHED
SIGNATURE_VERIFIED
DOCUMENT_HASH_LOCKED
VERSION_LOCKED
PUBLIC_VERIFICATION_ENABLED
```

Signature-related signal evidence must include `target_file_id` — the specific `FileObject` the signature covers (the uploaded scan, never the generated PDF).

## Signature

Represents a signature attached to a file or document version.

```text
Signature
- id
- document_version_id
- original_file_id
- signature_file_id
- signature_format
- country
- provider
- certificate_subject
- certificate_issuer
- valid_from
- valid_to
- verification_status
- verified_at
- raw_metadata_json
- created_at
```

## ShareLink

Represents a public or private verification link.

```text
ShareLink
- id
- document_id
- document_version_id
- token_hash
- visibility
- expires_at
- revoked_at
- created_at
```

`document_version_id` is pinned at creation. The verification page must display the exact version number and lock date of the pinned version.

## ConsentRecord

Represents an explicit consent decision by a requester or recommender.

```text
ConsentRecord
- id
- subject_type
- user_id (nullable)
- recommender_contact_id (nullable)
- reference_request_id (nullable)
- consent_type
- policy_text_version
- region
- status
- granted_at
- declined_at
- withdrawn_at
```

`reference_request_id` links a consent record to a specific reference request. It is set for request-scoped consents (e.g. the requester's verbal-consent attestation, which gates sending the invitation for exactly that request) and null for consents that are not bound to a single request.

Subject types:

```text
REQUESTER
RECOMMENDER
```

Attribution constraint: `subject_type` determines which identifier must be present. Exactly one of `user_id` or `recommender_contact_id` must be non-null — `REQUESTER` maps to `user_id`; `RECOMMENDER` maps to `recommender_contact_id`. Both-null and both-set states are invalid and must be rejected at the application layer.

Consent types:

```text
REQUESTER_VERBAL_CONSENT_ATTESTATION
RECOMMENDER_PROCESSING_CONSENT
RECOMMENDER_PUBLIC_SHARING_CONSENT
CROSS_BORDER_TRANSFER_CONSENT
```

Statuses:

```text
GRANTED
DECLINED
WITHDRAWN
```

Rules:

- consent texts are versioned per region (152-FZ RU, GDPR EU, GLOBAL); `policy_text_version` records the accepted version;
- an invitation cannot be sent without a `REQUESTER_VERBAL_CONSENT_ATTESTATION` recorded at request creation;
- the recommender must record `RECOMMENDER_PROCESSING_CONSENT` (or an explicit decline, which moves the request to `DECLINED`) after email confirmation and before answering;
- `RECOMMENDER_PUBLIC_SHARING_CONSENT` is optional and covers public pages/downloads of the recommender's uploads;
- withdrawal of consent triggers retraction of the affected content and signals.

## DataSubjectRequest

Represents a privacy request from a data subject (account holders and account-less recommenders).

```text
DataSubjectRequest
- id
- type
- status
- region
- subject_email
- user_id (nullable)
- recommender_contact_id (nullable)
- verified_at
- due_at
- resolution_notes
- created_at
- updated_at
```

Types:

```text
DELETION
EXPORT
REGION_MIGRATION
CONSENT_WITHDRAWAL
CORRECTION
```

Statuses:

```text
RECEIVED → IN_REVIEW → APPROVED → EXECUTED
                     → REJECTED
```

`due_at` is derived from the per-region SLA.

## AuditEvent

Append-only event log.

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

Audit events must be created for every sensitive action.
