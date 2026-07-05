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
- CSRF protection (required for all cookie-based sessions);
- server-side sessions;
- session expiration;
- session revocation;
- token hashing at rest;
- rate limiting;
- login attempt tracking;
- audit events.

Magic link tokens and invitation tokens must be stored hashed, not plaintext. Recommender
DSR verification codes follow the same rule (TokenHasher HMAC, never plaintext).

Follow-up (deferred): step-up re-authentication for account-holder DSR submission. The MVP
bar is the authenticated session plus the full audit trail (`DATA_SUBJECT_REQUEST_RECEIVED`);
a step-up challenge before destructive DSRs (DELETION/EXPORT) ships with the admin/review
iteration. The account-less recommender DSR channel is already verified by an emailed 6-digit
code and answers `202` on intake regardless of match (anti-enumeration).

## Admin Access

Admin and support access rules (separate per-region admin accounts, mandatory MFA, audited reads, no modification of locked versions or signals) are defined in `AUTHENTICATION.md` (Admin & Support Access).

## Encryption & Secrets

Requirements:

- TLS 1.2+ for all connections (external and service-to-service);
- at-rest encryption for PostgreSQL, object storage, and backups;
- per-cell secrets stored in a vault/KMS — never in code, config repos, or shared across cells;
- `ip_hash`/`user_agent_hash` use keyed HMAC with a per-cell secret pepper;
- pepper rotation is defined per cell (rotated at least annually and on suspected compromise; old pepper retained only long enough to rotate stored hashes or expire them);
- keyed hashes remain personal data under GDPR.

## Backups & Disaster Recovery

Requirements:

- backup residency equals cell residency — backups never leave the cell's jurisdiction;
- backups are encrypted;
- restore tests are performed periodically;
- backup deletion follows the erasure model in `PRIVACY_AND_DATA_CLASSIFICATION.md`.

## Link Lifetimes

- Magic links and pre-signed download URLs are short-lived.
- Share links are durable but revocable, and optionally expiring.

## Share-Token Hygiene

Requirements:

- token URLs are excluded or redacted from access logs;
- verification pages set `Referrer-Policy: no-referrer`;
- only `token_hash` is persisted — never the raw share token.

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

- versions are locked only after the recipient accepts the response in NEEDS_REVIEW (see `WORKFLOWS.md`); PDF generation, hashing, version locking, and signal creation happen only after that acceptance;
- locked versions are immutable;
- corrections happen via a new response cycle producing a new document version — locked versions are never edited;
- recommender-confirmed content cannot be silently edited by requester;
- signatures are linked to file hashes;
- verification pages must show the exact document version;
- public page enablement is an explicit recipient action, never automatic.

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
- keyed-HMAC hashed IP/user-agent only (never raw values).

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
- public verification page viewed (aggregated/sampled; full audit rows only for downloads and state changes — see `AUDIT_EVENTS.md`);
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
