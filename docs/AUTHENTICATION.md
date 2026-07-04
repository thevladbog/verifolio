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

## Region Routing at Login

Users log in on region-specific app domains:

```text
app.eu.verifolio.com
app.ru.verifolio.com
```

Rules:

- the stateless global layer only offers region selection;
- there is no global email→region directory in v1 — the user must know (or select) their region;
- all auth state (tokens, sessions, login events) lives in the selected regional cell.

See ADR-0008 for the rationale and rejected alternatives.

## User Login

Initial login method:

```text
Email magic link
```

Flow:

1. User enters email on the regional app domain.
2. Backend creates a short-lived magic link token.
3. Email is sent through the regional mail provider.
4. User opens the link.
5. Backend validates token.
6. Backend creates a secure server-side session.
7. Browser receives a secure HTTP-only cookie.

Magic link rules:

- single-use: the token is consumed on first successful validation;
- bounded TTL (15 minutes);
- requesting a new magic link invalidates all prior unconsumed tokens for that email (post-MVP: invalidation will also be scoped by `purpose` once that field is persisted);
- the response to a magic link request is identical whether or not the email has an account (anti-enumeration).

## Session Requirements

Sessions must be:

- server-side;
- stored in regional PostgreSQL;
- linked to user account;
- revocable;
- rotatable;
- expiration-based;
- protected with secure HTTP-only cookies;
- protected against CSRF (required for all cookie-based sessions).

Canonical Session model:

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

Raw IP and user-agent are never stored. `ip_hash` and `user_agent_hash` are keyed HMAC values using a per-cell secret pepper with a defined rotation schedule (see `SECURITY.md`). Even keyed hashes remain personal data under GDPR and are classified accordingly.

## Recommender Access

A recommender does not need a full account.

Opening the invite link is NOT identity confirmation. The invite link only starts a fresh email confirmation step.

Flow:

1. User sends a reference request (requires the requester's verbal-consent attestation; see `PRIVACY_AND_DATA_CLASSIFICATION.md`).
2. Backend creates an expiring invitation token.
3. Recommender receives a unique link.
4. Recommender opens the link; backend triggers a fresh email confirmation (one-time code or secondary magic link sent to the recommender address).
5. Recommender completes the email confirmation; backend mints a short-lived server-side recommender session cookie and consumes the invitation token. The invitation token is never reused as a bearer credential across the multi-step response flow.
6. Consent gate: before entering any answers, the recommender must explicitly ACCEPT the region's data-processing policy (ConsentRecord RECOMMENDER_PROCESSING_CONSENT) or explicitly DECLINE. Decline moves the request to DECLINED, stops reminders, emits CONSENT_DECLINED, and schedules recommender PII for erasure.
7. Recommender fills the response form under the recommender session.
8. Recommender submits and confirms the statement.
9. Backend creates document evidence and audit events.

The RECOMMENDER_EMAIL_CONFIRMED audit event evidences exactly this: someone with control of the recommender email inbox completed the fresh confirmation step at that time. It does not evidence legal identity.

Invitation tokens must be:

- unique;
- short-lived or configurable;
- revocable;
- single-use: consumed at email confirmation, never reused afterward;
- bound to request ID;
- rate-limited.

### Save Progress & Resume

- Draft answers are keyed to the invitation (request ID), not to the token or session.
- When the recommender returns after the recommender session expires, a new email confirmation is required before the draft is shown (re-confirmation on return).
- Drafts expire with the request (day 21). Before expiry, reminder emails include a grace warning that the draft will be deleted; expired drafts are erased with the recommender PII erasure rules.

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

## Admin & Support Access

Rules:

- admin accounts are separate per region — no cross-region admin identity;
- MFA is mandatory for all admin accounts;
- every admin read of user data is audited;
- admins cannot modify locked document versions or verification signals;
- admin actions follow the same domain authorization model.

## Step-Up Re-Confirmation

Destructive actions require a fresh confirmation (new magic link or one-time code) even within an active session:

- share-link revocation;
- account deletion;
- data subject request submission.

The step-up confirmation is single-use and short-lived, and its consumption is audited.

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

Session (canonical model — see Session Requirements)
- id
- user_account_id
- created_at
- expires_at
- last_seen_at
- ip_hash
- user_agent_hash
- revoked_at

MagicLinkToken
- id
- email
- token_hash
- purpose          (deferred post-MVP — not persisted in v1)
- expires_at
- consumed_at
- invalidated_at   (nullable; set when a newer link is issued for the same email)
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
