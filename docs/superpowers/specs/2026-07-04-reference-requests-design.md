# Reference Requests (Requester Side) — Design

Date: 2026-07-04
Status: approved
Scope: MVP roadmap item "Reference requests, including the consent model" — requester side only.

## Goal

Implement the requester side of the reference request lifecycle: create a request with the
blocking verbal-consent attestation, send the invitation email with a real single-use
invitation token link, cancel, list, and view requests. The recommender flow (token
consumption, email confirmation, consent gate, answers, submission) is the next iteration.

Explicitly out of scope: Temporal workflows (reminders, auto-expiration enforcement),
recommender-side endpoints, reference responses, documents.

## Architecture

### Module: `requests` (new implementation)

Layout mirrors `contacts`: `api/` (controller, DTOs), `application/` (service),
`domain/` (status enum, transition policy), `infrastructure/` (jOOQ repository).

Owns tables `reference_request` and `consent_record`, the request state machine, and all
requester endpoints.

### Module: `identity` (extended)

Owns the `invitation_token` table per `AUTHENTICATION.md`. New public API at package root:

```kotlin
interface InvitationTokenService {
    fun mint(requestId: UUID, recommenderEmail: String, ttl: Duration): String // raw token
    fun revokeForRequest(requestId: UUID): Int // number revoked
}
```

Raw tokens are returned to the caller only; the DB stores an HMAC hash via the existing
`TokenHasher`. Revocation audits `INVITATION_TOKEN_REVOKED` per token.

### New public APIs on existing modules

`requests` never touches another module's tables. It consumes:

- `contacts.ContactLookup.findOwned(contactId, ownerProfileId): ContactSnapshot?`
  (returns id, name, email) — new package-root interface;
- `templates.TemplateLookup.exists(templateId): Boolean` — new package-root interface;
- `profiles.ProfileService.requireProfileId` (existing);
- `notifications.MailPort` (existing);
- `audit.AuditService` (existing).

### Configuration (`VerifolioProperties`)

```yaml
verifolio:
  consents:
    requester-attestation:
      text-id: local-requester-attestation
      version: 1
  requests:
    expiry: P21D
    send-limit-per-recommender: 5     # sliding window
    send-limit-window: P1D
```

Consent text identifiers are versioned per region (`REGION_POLICIES.md`); the deployed
cell's values come from configuration, the region value from existing `verifolio.region`.

## Data Model (Flyway V4)

### `reference_request`

Per `DATA_MODEL.md`: `id` (uuid pk), `requester_profile_id` (FK `person_profile`),
`recommender_contact_id` (FK `recommender_contact`, ON DELETE RESTRICT),
`template_id` (FK `template`), `purpose` (text), `status` (text + CHECK on the 11 canonical
values), `expires_at`, `created_at`, `updated_at`.
Index `(requester_profile_id, created_at DESC, id)` for keyset pagination.

ON DELETE RESTRICT: a contact with reference requests cannot be deleted; DSR erasure will
go through the privacy module later with its own tombstoning rules.

### `consent_record`

Per `DATA_MODEL.md`: `id`, `subject_type` (REQUESTER|RECOMMENDER), `user_id` (nullable),
`recommender_contact_id` (nullable), `consent_type`, `policy_text_version`, `region`,
`status` (GRANTED|DECLINED|WITHDRAWN), `granted_at`, `declined_at`, `withdrawn_at`.

CHECK constraints: exactly one of `user_id` / `recommender_contact_id` is non-null, and
`subject_type` matches the populated column (REQUESTER → `user_id`,
RECOMMENDER → `recommender_contact_id`).

**Spec extension:** nullable `reference_request_id` (FK `reference_request`,
ON DELETE RESTRICT) links a consent record to a specific request. Required to enforce
"an invitation cannot be sent without a `REQUESTER_VERBAL_CONSENT_ATTESTATION` recorded at
request creation" per request. `DATA_MODEL.md` is updated in this change.

### `invitation_token`

Per `AUTHENTICATION.md`: `id`, `request_id` (FK `reference_request`), `recommender_email`,
`token_hash` (unique), `expires_at`, `consumed_at`, `revoked_at`, `created_at`.

## API Contract

All endpoints are session-authenticated and owner-scoped (requester profile resolved via
`ProfileService.requireProfileId`). CSRF rules apply as everywhere else.

### `POST /api/v1/reference-requests` → 201

Body: `recommenderContactId`, `templateId`, `purpose` (≤ 2000 chars, optional),
`verbalConsentAttested` (boolean, must be `true`).

Behaviour (single transaction):
1. Validate contact ownership (`ContactLookup`), template existence (`TemplateLookup`).
2. Reject `verbalConsentAttested != true` with 400 `CONSENT_REQUIRED`.
3. Insert request with status `CREATED`, `expires_at = now + verifolio.requests.expiry`.
4. Insert consent record: REQUESTER / `user_id` = caller /
   `REQUESTER_VERBAL_CONSENT_ATTESTATION` / GRANTED / configured text version / region /
   `reference_request_id` set.
5. Audit `REFERENCE_REQUEST_CREATED` (entity REFERENCE_REQUEST) and `CONSENT_GRANTED`
   (entity CONSENT_RECORD, metadata: `consentType`, `policyTextVersion`, `region`,
   `referenceRequestId`).

### `POST /api/v1/reference-requests/{id}/send` → 200

1. Load owned request; 404 if not found/not owned.
2. Require status `CREATED`; otherwise 409 `INVALID_REQUEST_STATE`.
3. Defense-in-depth: verify the attestation consent record exists for this request.
4. Enforce global per-recommender-email send rate limit (sliding window, in-process,
   configurable); 429 `RATE_LIMITED` on breach.
5. Mint invitation token (TTL = time until `expires_at`).
6. Send invitation email via `MailPort`: who requested, purpose, link
   `${frontendBaseUrl}/invitations/{rawToken}`, plus decline and report-abuse links
   (frontend URLs; their backend endpoints ship with the recommender-flow iteration —
   accepted risk, noted below).
7. Status → `SENT`; audit `REFERENCE_REQUEST_SENT`.

### `POST /api/v1/reference-requests/{id}/cancel` → 200

Allowed from any non-terminal status (per `WORKFLOWS.md` transition table); otherwise 409
`INVALID_REQUEST_STATE`. Status → `CANCELLED`, all outstanding invitation tokens revoked
(`InvitationTokenService.revokeForRequest`), audit `REFERENCE_REQUEST_CANCELLED` (+
`INVITATION_TOKEN_REVOKED` from identity).

### `GET /api/v1/reference-requests` → 200

Keyset-cursor pagination identical to contacts (cursor = Base64(`ISO-createdAt|id`),
page size 50, lookahead hasNext). Optional `status` filter. Owner-scoped.

### `GET /api/v1/reference-requests/{id}` → 200

Owner-scoped single fetch; 404 otherwise.

### DTO

`ReferenceRequestResponse`: `id`, `recommenderContactId`, `templateId`, `purpose`,
`status`, `expiresAt`, `createdAt`, `updatedAt`. Recommender PII is not duplicated into the
DTO — clients join via the contacts API.

### Errors

`CONSENT_REQUIRED` (400), `VALIDATION_FAILED` (400), `NOT_FOUND` (404),
`INVALID_REQUEST_STATE` (409), `RATE_LIMITED` (429) — via existing `ApiException`/`ApiError`.

## State Machine

Canonical statuses and transitions per `DATA_MODEL.md`/`WORKFLOWS.md`. This iteration
implements transitions: `CREATED → SENT` (send), `any non-terminal → CANCELLED` (cancel).
The domain layer encodes the full status enum and a transition policy
(`ReferenceRequestStatus.canTransitionTo`) so later iterations only add triggers, not rules.
Terminal statuses: `COMPLETED`, `DECLINED`, `EXPIRED`, `CANCELLED`.

## Audit Events

All from the canonical catalog (`AUDIT_EVENTS.md`): `REFERENCE_REQUEST_CREATED`,
`CONSENT_GRANTED`, `REFERENCE_REQUEST_SENT`, `REFERENCE_REQUEST_CANCELLED`,
`INVITATION_TOKEN_REVOKED`. Metadata contains IDs, statuses, consent type, policy text
version, and region only — never contact names or emails (same PII discipline as contacts).

## Security

- Authorization: every read/write filtered by `requester_profile_id` of the caller.
- Tokens: raw invitation token appears only in the email body; DB stores HMAC hash;
  no token logging.
- Rate limiting: global per-recommender-email send limit across all requesters
  (anti-spam requirement from `RECOMMENDER_EXPERIENCE.md`); in-process sliding window,
  same limitation as existing auth rate limits (documented deferred item).
- Region: consent record stores the cell's region; no cross-region flows introduced.

## Testing

- Unit: state machine transition policy.
- Integration (Testcontainers, extend `testsupport.IntegrationTest`,
  `RecordingMailConfig`):
  - create: happy path (consent record + audit events persisted), missing attestation
    → 400, foreign/unknown contact or template → 404/400, unauthenticated → 401;
  - send: happy path (email recorded with tokenized link, status SENT, token row hashed),
    double send → 409, rate limit breach → 429;
  - cancel: from CREATED and SENT (token revoked), from CANCELLED → 409;
  - list/get: owner isolation, keyset pagination, status filter;
  - consent record DB constraints (both-null / both-set rejected);
  - modularity: `ModularityTests` still green with new cross-module API usage.
- OpenAPI snapshot refreshed and guarded by `OpenApiContractTest`.

## Documentation Updates

- `DATA_MODEL.md`: implementation status + `ConsentRecord.reference_request_id` field;
- `docs/agent/IMPLEMENTATION_HISTORY.md`: iteration 3 entry;
- `docs/ROADMAP.md`: mark the requester side as delivered (recommender flow remains open).

## Risks / Accepted Trade-offs

- Decline / report-abuse links in the invitation email point to frontend routes whose
  backend endpoints arrive with the recommender-flow iteration; until then they are
  documented placeholders. Reminders do not exist yet, so the stop-reminders obligation is
  not yet triggered.
- No auto-expiration: `expires_at` is stored; `send` rejects a request whose `expires_at`
  is in the past (409 `INVALID_REQUEST_STATE`), while `cancel` remains allowed. The
  `EXPIRED` status transition itself ships with the "minimal workflows" item.
- Rate limiting is in-process (single instance) — same tracked limitation as auth.
