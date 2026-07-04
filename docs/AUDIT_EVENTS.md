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

```text
USER_ACCOUNT
PROFILE
REFERENCE_REQUEST
REFERENCE_RESPONSE
DOCUMENT
DOCUMENT_VERSION
FILE_OBJECT
SIGNATURE
SHARE_LINK
VERIFICATION_SIGNAL
SESSION
MAGIC_LINK
INVITATION_TOKEN
```

## Required Events

### Authentication

```text
MAGIC_LINK_REQUESTED
MAGIC_LINK_CONSUMED
LOGIN_SUCCEEDED
LOGIN_FAILED
LOGOUT
SESSION_REVOKED
```

### Reference Requests

```text
REFERENCE_REQUEST_CREATED
REFERENCE_REQUEST_SENT
REFERENCE_REQUEST_OPENED
REFERENCE_REQUEST_REVOKED
REFERENCE_REQUEST_EXPIRED
```

### Recommender Response

```text
RECOMMENDER_EMAIL_CONFIRMED
REFERENCE_RESPONSE_STARTED
REFERENCE_RESPONSE_SUBMITTED
RECIPIENT_CONFIRMED_BY_RECOMMENDER
RELATIONSHIP_CONFIRMED_BY_RECOMMENDER
```

### Documents

```text
DOCUMENT_CREATED
DOCUMENT_VERSION_CREATED
DOCUMENT_VERSION_LOCKED
DOCUMENT_PDF_GENERATED
DOCUMENT_SHARED
DOCUMENT_SHARE_REVOKED
```

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
PUBLIC_VERIFICATION_PAGE_VIEWED
PUBLIC_VERIFICATION_PAGE_DOWNLOAD
```

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

## AI-Agent Rule

Any new sensitive action must define an audit event.
