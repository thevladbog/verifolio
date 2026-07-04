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
api/v1/documents
/api/v1/documents/{id}
api/v1/documents/{id}/versions
/api/v1/files/{id}/download-url
/api/v1/verification-pages/{token}
```

## Command Endpoints

Use explicit commands for important state changes:

```text
POST /api/v1/reference-requests/{id}/send
POST /api/v1/reference-requests/{id}/revoke
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
TOKEN_EXPIRED
TOKEN_REVOKED
DOCUMENT_VERSION_LOCKED
FILE_ACCESS_DENIED
REGION_POLICY_VIOLATION
VERIFICATION_SIGNAL_INVALID
SIGNATURE_VERIFICATION_FAILED
```

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

## OpenAPI

Every endpoint must be documented in OpenAPI.

When changing API:

- update OpenAPI spec;
- update generated client;
- update tests;
- update docs if behavior changed.

## AI-Agent Rule

Do not create undocumented endpoints.
