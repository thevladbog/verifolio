# Error Handling

## Goals

Errors should be:

- safe;
- actionable;
- consistent;
- traceable;
- not leaking sensitive data.

## Error Categories

```text
AUTHENTICATION
AUTHORIZATION
VALIDATION
NOT_FOUND
CONFLICT
REGION_POLICY
EXTERNAL_PROVIDER
WORKFLOW
STORAGE
SIGNATURE
INTERNAL
```

## Error Response

```json
{
  "error": {
    "code": "TOKEN_EXPIRED",
    "message": "This link has expired.",
    "requestId": "req_abc123",
    "details": {}
  }
}
```

## User-Facing Messages

Do:

```text
This verification link has expired. Ask the document owner to send a new link.
```

Do not:

```text
Temporal workflow wf_123 failed at activity SignatureActivity with NullPointerException.
```

## Logging

Logs should include:

- request ID;
- actor ID if available;
- entity ID;
- error code;
- safe metadata.

Logs must not include:

- raw tokens;
- session IDs;
- document contents;
- file contents;
- private URLs;
- passwords.

## Common Domain Errors

### DOCUMENT_VERSION_LOCKED

A locked document version cannot be changed.

### FILE_ACCESS_DENIED

Actor is not allowed to access the requested file.

### REGION_POLICY_VIOLATION

Operation would send/process regional data in a forbidden location.

### TOKEN_EXPIRED

Magic link, invitation link, or share link is expired.

### TOKEN_REVOKED

Invitation/share link has been revoked.

### SIGNATURE_VERIFICATION_FAILED

Signature validation failed or provider could not verify the signature.

## Retryable Errors

Retry may be allowed for:

- mail provider transient failure;
- object storage transient failure;
- Temporal activity transient failure;
- OCR/signature provider temporary unavailability.

Retry must not create duplicate sensitive effects.

## AI-Agent Rule

When adding a new error code:

- document it here;
- add tests;
- ensure API response consistency.
