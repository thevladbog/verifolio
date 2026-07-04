# Authentication & Access Model

## Decision

Verifolio v1 will not use Keycloak.

Authentication will be implemented as a lightweight embedded module inside the Kotlin/Spring backend using Spring Security.

## Rationale

The product must be deployable per region and must keep user identity data in the selected jurisdiction.

Managed auth providers and centralized identity systems can complicate data residency because they may store:

- email addresses;
- phone numbers;
- session metadata;
- login events;
- IP addresses;
- device data;
- audit trails.

The initial Verifolio auth model is simple enough to implement locally:

- email magic links;
- secure server-side sessions;
- recommender invitation links;
- optional password login later.

## Auth Principles

1. Authentication identifies the actor.
2. Domain authorization decides what the actor can do.
3. Recommenders should not be forced to create full accounts.
4. Session and token data must stay in the selected regional deployment.
5. All sensitive auth events must be audited.
6. Auth must be replaceable or extendable later through an `AuthIdentity` model.

## User Login

Initial login method:

```text
Email magic link
```

Flow:

1. User enters email.
2. Backend creates a short-lived magic link token.
3. Email is sent through the regional mail provider.
4. User opens the link.
5. Backend validates token.
6. Backend creates a secure server-side session.
7. Browser receives a secure HTTP-only cookie.

## Session Requirements

Sessions must be:

- server-side;
- stored in regional PostgreSQL;
- linked to user account;
- revocable;
- rotatable;
- expiration-based;
- protected with secure HTTP-only cookies;
- protected against CSRF where applicable.

Session metadata:

```text
Session
- id
- user_account_id
- created_at
- expires_at
- last_seen_at
- ip_hash
- user_agent_hash
- revoked_at
```

Avoid storing raw IP/user-agent unless necessary. Prefer hashing or minimization.

## Recommender Access

A recommender does not need a full account.

Flow:

1. User sends a reference request.
2. Backend creates an expiring invitation token.
3. Recommender receives a unique link.
4. Recommender confirms email.
5. Recommender fills the response form.
6. Recommender submits and confirms the statement.
7. Backend creates document evidence and audit events.

Invitation tokens must be:

- unique;
- short-lived or configurable;
- revocable;
- single-use where appropriate;
- bound to request ID;
- rate-limited.

## Domain Authorization

Business permissions are handled inside backend services, not by the auth provider.

Examples:

```text
can_view_document
can_edit_request
can_submit_reference
can_upload_scan
can_download_file
can_view_public_verification_page
can_revoke_share_link
can_lock_document_version
can_verify_signature
```

## Auth Data Model

```text
UserAccount
- id
- region
- primary_email
- status
- created_at
- updated_at

AuthIdentity
- id
- user_account_id
- provider
- external_subject
- email
- email_verified
- created_at

Session
- id
- user_account_id
- expires_at
- revoked_at
- created_at
- last_seen_at

MagicLinkToken
- id
- email
- token_hash
- purpose
- expires_at
- consumed_at
- created_at

InvitationToken
- id
- request_id
- recommender_email
- token_hash
- expires_at
- consumed_at
- revoked_at
- created_at
```

## Future Auth Extensions

The model must allow adding:

- password login;
- passkeys;
- TOTP MFA;
- Google login;
- GitHub login;
- LinkedIn login;
- external OIDC provider;
- enterprise SSO per region;
- Logto or SuperTokens if needed.

## What Not to Do

Do not:

- store auth data globally;
- use public JWTs as the only session model for sensitive flows;
- allow unrestricted token reuse;
- make recommenders create accounts before submitting references;
- put document permissions into frontend code only;
- allow public object storage URLs;
- bypass domain authorization checks.
