# Core Domain Model

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
LOGTO
SUPERTOKENS
ENTERPRISE_SSO
```

## RecommenderContact

Represents a person who can provide references.

```text
RecommenderContact
- id
- owner_profile_id
- name
- email
- company_name
- company_domain
- title
- relationship_type
- created_at
- updated_at
```

## ReferenceRequest

Represents a request sent to a recommender.

```text
ReferenceRequest
- id
- requester_profile_id
- recommender_contact_id
- template_id
- purpose
- status
- expires_at
- created_at
- updated_at
```

Statuses:

```text
DRAFT
SENT
OPENED
IN_PROGRESS
SUBMITTED
NEEDS_REVIEW
VERIFIED
REJECTED
EXPIRED
REVOKED
```

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

## Document

Represents a professional proof document.

```text
Document
- id
- owner_profile_id
- request_id
- type
- status
- current_version_id
- created_at
- updated_at
```

Document types:

```text
REFERENCE_LETTER
EMPLOYMENT_PROOF
IMMIGRATION_REFERENCE
VISA_SUPPORT_LETTER
ACADEMIC_RECOMMENDATION
CLIENT_TESTIMONIAL
CHARACTER_REFERENCE
```

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
- locked_at
- locked_by_actor_id
- created_at
```

Domain rule:

```text
Once locked_at is set, the version must never be modified.
```

Any change creates a new version.

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
- uploaded_by_actor_id
- created_at
```

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

Entity types:

```text
USER_PROFILE
RECOMMENDER
ORGANIZATION
DOCUMENT
DOCUMENT_VERSION
FILE
SIGNATURE
```

Signal types:

```text
EMAIL_CONFIRMED
CORPORATE_DOMAIN_CONFIRMED
PHONE_CONFIRMED
PROFILE_IDENTITY_VERIFIED
RECOMMENDER_RELATIONSHIP_CONFIRMED
RECIPIENT_CONFIRMED
SCAN_ATTACHED
SIGNATURE_ATTACHED
SIGNATURE_VERIFIED
DOCUMENT_HASH_LOCKED
VERSION_LOCKED
PUBLIC_VERIFICATION_ENABLED
```

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
- token_hash
- visibility
- expires_at
- revoked_at
- created_at
```

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
