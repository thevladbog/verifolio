# Security Principles

## Security Model

Verifolio is a document-trust platform. Security is a core product feature.

The system handles:

- personal data;
- professional references;
- signed documents;
- scans;
- email addresses;
- identity signals;
- verification links;
- audit logs.

## Core Principles

1. Privacy by default.
2. Region-local processing.
3. Least privilege.
4. No public object storage URLs.
5. Explicit domain authorization.
6. Immutable locked document versions.
7. Append-only audit events for sensitive actions.
8. Short-lived sensitive links.
9. Secure server-side sessions.
10. Clear trust disclaimers.

## Authentication Security

Requirements:

- secure HTTP-only cookies;
- SameSite protection;
- CSRF protection where applicable;
- server-side sessions;
- session expiration;
- session revocation;
- token hashing at rest;
- rate limiting;
- login attempt tracking;
- audit events.

Magic link tokens and invitation tokens must be stored hashed, not plaintext.

## Authorization Security

Every sensitive operation must check domain authorization.

Examples:

- view document;
- edit request;
- submit reference;
- upload scan;
- download file;
- create share link;
- revoke share link;
- view verification page;
- verify signature;
- lock document version.

Frontend checks are not security controls.

## File Security

Requirements:

- private buckets;
- no public object URLs;
- pre-signed URLs only after backend authorization;
- short-lived download links;
- MIME validation;
- size limits;
- malware scanning where available;
- hash calculation;
- audit events.

## Document Integrity

Rules:

- locked versions are immutable;
- changing confirmed content creates a new version;
- recommender-confirmed content cannot be silently edited by requester;
- signatures are linked to file hashes;
- verification pages must show the exact document version.

## Public Verification Pages

A verification page must show evidence, not make absolute claims.

Recommended disclaimer:

```text
Verifolio verifies identity signals, recommender confirmation methods, and document integrity. It does not independently guarantee the truth of every statement inside the document.
```

## Logging

Logs must not contain:

- full magic link tokens;
- invitation tokens;
- raw session IDs;
- document contents;
- file contents;
- private download URLs;
- unredacted sensitive personal data.

Prefer:

- request IDs;
- actor IDs;
- entity IDs;
- hashed IP/user-agent where necessary.

## Audit Events

Audit events are required for:

- login;
- logout;
- magic link requested;
- request sent;
- request opened;
- response submitted;
- file uploaded;
- document generated;
- document locked;
- signature attached;
- signature verified;
- share link created;
- share link revoked;
- public verification page viewed;
- file downloaded.

## AI/OCR Security

AI/OCR features must be region-aware.

Documents must not be sent to external AI/OCR providers unless:

- the provider is allowed for the region;
- the user has consented where required;
- the flow is documented;
- the data processing basis is approved.

## Security Testing

Minimum tests:

- unauthorized document access;
- revoked link access;
- expired link access;
- recommender access isolation;
- cross-user file download prevention;
- locked document modification prevention;
- token reuse prevention;
- rate limiting behavior.

## AI-Agent Rule

AI agents must not implement security-sensitive changes without tests.
