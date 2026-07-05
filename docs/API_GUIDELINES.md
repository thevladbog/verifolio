# API Guidelines

## API Style

Verifolio uses REST + OpenAPI.

## Principles

- Use clear resource names.
- Use stable DTOs.
- Do not expose internal domain classes.
- Do not expose object storage keys unless required and safe.
- Use explicit error codes.
- Use pagination for lists.
- Use idempotency keys for retryable commands where appropriate.

## Resource Naming

Examples:

```text
/api/v1/profile
/api/v1/reference-requests
/api/v1/reference-requests/{id}
/api/v1/reference-requests/{id}/response  (read-only; owner reviews the latest submitted
                                           response — upload metadata only, no pre-accept
                                           file downloads; 404 until a submission exists)
/api/v1/documents
/api/v1/documents/{id}
/api/v1/documents/{id}/versions
/api/v1/files/{id}/download-url
/api/v1/verification-pages/{token}
/api/v1/templates
/api/v1/templates/{id}
/api/v1/consent-texts/{consentType}     (open/permitAll, read-only; region-configured consent
                                         policy text — ?locale= falls back to en; unknown
                                         consentType → 404; reads are not audited)
/api/v1/contacts
/api/v1/contacts/{id}
/api/v1/verification-signals            (read-only)
/api/v1/documents/{id}/verification-signals (read-only)
/api/v1/share-links
/api/v1/share-links/{id}
/api/v1/consent-records
/api/v1/consent-records/{id}
```

Auth:

```text
POST   /api/v1/auth/magic-links
POST   /api/v1/auth/sessions
DELETE /api/v1/auth/sessions/current
```

Invitations (recommender flow). The invitation token is a credential only until email
confirmation, where it is consumed single-use (`AUTHENTICATION.md`). The flow then runs
under the recommender session cookie — except the email-only one-click
`decline` / `report-abuse` links, which stay token-scoped and keep working after
consumption while the request is non-terminal:

```text
GET  /api/v1/invitations/{token}                      (open; pre-session)
POST /api/v1/invitations/{token}/email-confirmations  (send one-time code)
POST /api/v1/invitations/{token}/confirm-email        (consumes token, mints session)
POST /api/v1/invitations/{token}/decline              (one-click from email; works post-consumption)
POST /api/v1/invitations/{token}/report-abuse         (one-click from email)

GET  /api/v1/recommender/request                      (session-scoped)
POST /api/v1/recommender/consent
PUT  /api/v1/recommender/response-draft
POST /api/v1/recommender/responses
```

File uploads:

```text
POST /api/v1/files/upload-requests
```

Data subject requests:

```text
POST /api/v1/data-subject-requests
```

Admin API uses a dedicated prefix with separate authorization:

```text
/api/v1/admin/...
```

## Command Endpoints

Use explicit commands for important state changes:

```text
POST /api/v1/reference-requests/{id}/send
POST /api/v1/reference-requests/{id}/cancel
POST /api/v1/documents/{id}/share-links
POST /api/v1/share-links/{id}/revoke
POST /api/v1/document-versions/{id}/lock
```

## Error Response Format

Recommended:

```json
{
  "error": {
    "code": "DOCUMENT_VERSION_LOCKED",
    "message": "This document version is locked and cannot be modified.",
    "requestId": "req_123",
    "details": {}
  }
}
```

## Common Error Codes

```text
UNAUTHORIZED
FORBIDDEN
NOT_FOUND
VALIDATION_ERROR
CONFLICT                        (HTTP 409)
RATE_LIMITED                    (HTTP 429)
INTERNAL_ERROR                  (HTTP 500)
TOKEN_EXPIRED
TOKEN_REVOKED
DOCUMENT_VERSION_LOCKED
FILE_ACCESS_DENIED
REGION_POLICY_VIOLATION
VERIFICATION_SIGNAL_INVALID
SIGNATURE_VERIFICATION_FAILED
```

## Idempotency

Retryable command endpoints accept an `Idempotency-Key` header:

- the client generates a UUID per logical operation;
- a replayed request with the same key returns the original response (same status code and body), without repeating side effects;
- keys are retained for 24 hours; after the retention window a reused key is treated as a new request;
- reusing a key with a different request body returns `CONFLICT` (409).

## Authentication

Private API uses secure session cookies.

Public verification pages use opaque share tokens.

Recommender pages use invitation tokens.

## Pagination

Use cursor-based pagination for large lists.

Example:

```text
GET /api/v1/documents?limit=20&cursor=...
```

Response envelope:

```json
{
  "items": [],
  "nextCursor": "opaque-cursor-or-null"
}
```

`nextCursor` is `null` on the last page.

## OpenAPI

Every endpoint must be documented in OpenAPI.

When changing API:

- update OpenAPI spec;
- update generated client;
- update tests;
- update docs if behavior changed.

## AI-Agent Rule

Do not create undocumented endpoints.
