# Recommender Flow — Design

Date: 2026-07-04
Status: approved
Scope: MVP roadmap item "Recommender flow" — invitation open through response submission
(`SENT → OPENED → IN_PROGRESS → SUBMITTED → NEEDS_REVIEW`, plus `DECLINED`).

## Goal

Implement the recommender-side backend per `USER_FLOWS.md` Flow 3 / Flow 9 and
`RECOMMENDER_EXPERIENCE.md`: open the invitation, confirm email ownership with a one-time
code, mint a short-lived recommender session, pass the consent gate (processing +
optional cross-border), save draft answers, and submit the response with recipient and
relationship confirmations.

Out of scope: AI letter drafting (no AI providers), uploads/signatures (no files module),
recipient review and document generation (Documents iteration), reminders/auto-expiry
(Temporal item), physical PII erasure on decline (privacy module; audited status change
now, erasure deferred).

## Auth Model (per AUTHENTICATION.md — normative)

The invitation token is a credential only until email confirmation, where it is consumed
single-use. The multi-step response flow runs under a short-lived server-side
**recommender session** cookie (`verifolio_recommender_session`, HttpOnly/SameSite=Strict,
TTL `verifolio.auth.recommender-session-ttl`, default 1h), never under the invitation
token. `API_GUIDELINES.md` currently sketches token-scoped consent/response paths; it is
updated in this change to the session-scoped shape (AUTHENTICATION.md wins).

Email confirmation: 6-digit one-time code sent to the snapshotted recommender email.
Only the HMAC hash is stored; TTL 10 minutes; max 5 verification attempts per code;
issuing rate-limited (3 per 15 min per invitation).

## Data Model (Flyway V5)

### `recommender_session`

`id`, `request_id` (FK reference_request), `recommender_email`, `token_hash` (unique,
HMAC), `ip_hash`, `user_agent_hash`, `expires_at`, `revoked_at`, `created_at`.

### `email_confirmation_code`

`id`, `invitation_token_id` (FK invitation_token), `code_hash` (HMAC), `expires_at`,
`consumed_at`, `attempts` (int, default 0), `created_at`.

### `reference_response`

Per `DATA_MODEL.md`: `id`, `request_id` (FK), `recommender_email`, `answers_json` (jsonb),
`confirmation_text`, `relationship_confirmed` (bool), `recipient_confirmed` (bool),
`submitted_at`, plus `created_at`, `updated_at`.

**Spec extension:** `approved_letter_text` (text) — `RECOMMENDER_EXPERIENCE.md` requires
storing both the structured answers and the approved letter text; the entity model lacked
a field for the latter. `DATA_MODEL.md` is updated in this change.

A draft is a row with `submitted_at IS NULL`. Partial unique index: one draft per request
(`create unique index ... on reference_response (request_id) where submitted_at is null`).

## Module Boundaries

### `identity` (owns tokens, codes, sessions)

New package-root public API:

```kotlin
data class InvitationInfo(val requestId: UUID, val recommenderEmail: String)
data class RecommenderGrant(val rawSessionToken: String, val requestId: UUID, val recommenderEmail: String)
data class RecommenderActor(val requestId: UUID, val email: String) // principal

interface InvitationAccess {
    /** Validates without consuming; null if unknown/expired/revoked/consumed. */
    fun peek(rawToken: String): InvitationInfo?
    /** Generates and stores a one-time code; returns the RAW code for the caller to email. Rate-limited. */
    fun issueEmailConfirmation(rawToken: String): String
    /** Verifies the code, consumes the invitation token, mints a recommender session. Audited. */
    fun confirmEmail(rawToken: String, code: String, ipHash: String?, userAgentHash: String?): RecommenderGrant
}

interface RecommenderSessions {
    fun resolve(rawSessionToken: String): RecommenderActor?
    fun revokeForRequest(requestId: UUID): Int
}
```

`RecommenderSessionAuthFilter` (identity.api) resolves the recommender cookie into a
`RecommenderActor` principal, registered next to `SessionAuthFilter`. `SecurityConfig`:
`/api/v1/invitations/**` permitted (public entry points; the token-scoped POSTs join the
CSRF ignore list); `/api/v1/recommender/**` requires authentication; controllers reject a
non-`RecommenderActor` principal with 403.

Audit inside identity: `RECOMMENDER_EMAIL_CONFIRMED`, `INVITATION_TOKEN_CONSUMED`.

### `requests` (owns flow state, responses, consent records)

Two new controllers:

- `InvitationController` (`/api/v1/invitations/{token}`) — token-scoped, pre-session;
- `RecommenderFlowController` (`/api/v1/recommender`) — session-scoped.

Service `RecommenderFlowService` orchestrates status transitions via the existing
`ReferenceRequestStatus.canTransitionTo`.

### `profiles`

`ProfileService` gains `displayName(profileId: UUID): String?` for the invitation preview.

## API Contract

### Token-scoped (pre-session)

`GET /api/v1/invitations/{token}` → 200
Peek token; 404 `NOT_FOUND` if invalid/expired/revoked/consumed or request terminal.
First open transitions SENT→OPENED (+ `REFERENCE_REQUEST_OPENED`, actor RECOMMENDER).
Returns: `requesterName`, `purpose`, `templateName`, `recommenderEmailMasked`
(`j***@corp.example.com`), `status`.

`POST /api/v1/invitations/{token}/email-confirmations` → 202
Issues a code, emails it to the snapshotted recommender email via `MailPort`.
429 `RATE_LIMITED` over 3/15min per invitation.

`POST /api/v1/invitations/{token}/confirm-email` `{code}` → 200 + Set-Cookie
400 `CODE_INVALID` on wrong/expired code (attempts capped at 5 → `CODE_INVALID` with the
code row exhausted; the recommender requests a new code). Consumes the invitation token.

`POST /api/v1/invitations/{token}/decline` and `POST .../report-abuse` → 200
One-click from email links (already sent since iteration 3). Work even after the token is
consumed, as long as the request is non-terminal: status → DECLINED (audit
`REQUEST_DECLINED` with metadata `reason = declined | abuse_report`), all recommender
sessions and outstanding tokens for the request revoked. No consent record is written
(the consent gate was not necessarily reached).

### Session-scoped (`/api/v1/recommender`, `RecommenderActor` principal)

`GET /request` → 200 — request context: `status`, `requesterName`, `purpose`,
`templateName`, question schema, required consent texts from config
(`processing` always; `crossBorderTransfer` listed so the frontend can present it when
jurisdictions differ), and the current draft if any.

`POST /consent` `{accepted: Boolean, crossBorderAccepted: Boolean?}` → 200
Requires status OPENED (409 `INVALID_REQUEST_STATE` otherwise).
- `accepted=true`: writes `RECOMMENDER_PROCESSING_CONSENT` GRANTED (subject_type
  RECOMMENDER, `recommender_contact_id` from the request, versioned policy text, region),
  plus `CROSS_BORDER_TRANSFER_CONSENT` GRANTED when `crossBorderAccepted == true`;
  status → IN_PROGRESS. Audit `CONSENT_GRANTED` per record.
- `accepted=false`: writes `RECOMMENDER_PROCESSING_CONSENT` DECLINED; status → DECLINED;
  audit `CONSENT_DECLINED` + `REQUEST_DECLINED`; sessions revoked.

`PUT /response-draft` `{answersJson: object, approvedLetterText: String?}` → 200
Requires IN_PROGRESS. Upserts the draft row. First creation audits
`REFERENCE_RESPONSE_STARTED`; subsequent autosaves are not audited.

`POST /responses` `{approvedLetterText, confirmationText, recipientConfirmed,
relationshipConfirmed, answersJson?}` → 201
Requires IN_PROGRESS and an existing GRANTED processing consent (defense in depth).
Both confirmations must be `true` (400 `CONFIRMATION_REQUIRED` otherwise);
`approvedLetterText` non-blank. Single transaction: finalize the response row
(`submitted_at = now`), transition IN_PROGRESS→SUBMITTED→NEEDS_REVIEW (the SUBMITTED→
NEEDS_REVIEW hop is the automatic system transition), audit
`REFERENCE_RESPONSE_SUBMITTED`, `RECIPIENT_CONFIRMED_BY_RECOMMENDER`,
`RELATIONSHIP_CONFIRMED_BY_RECOMMENDER`, revoke the recommender sessions for the request.

### Errors

Existing codes plus new `CODE_INVALID` (400) and `CONFIRMATION_REQUIRED` (400). Existing
`NOT_FOUND`, `INVALID_REQUEST_STATE` (409), `RATE_LIMITED` (429), `VALIDATION_ERROR`,
`FORBIDDEN`.

## Configuration

```yaml
verifolio:
  auth:
    recommender-session-ttl: 1h
    email-confirmation-ttl: 10m
    email-confirmation-limit: 3        # per invitation per window
    email-confirmation-window: 15m
  consents:
    processing:
      text-id: local-processing
      version: 1
    cross-border-transfer:
      text-id: local-cross-border
      version: 1
```

## Security Notes

- Raw invitation tokens, codes, and session tokens are never stored or logged — HMAC via
  the existing `TokenHasher`.
- The recommender session grants access to exactly one request (`RecommenderActor.requestId`);
  every `/api/v1/recommender/*` operation is scoped by it.
- One-click decline is intentionally allowed with only the (possibly consumed) invitation
  token: it is a data-minimizing action explicitly required by the anti-spam rules.
- Consent gate ordering enforced by status: answers (draft/submit) require IN_PROGRESS,
  which is reachable only through consent acceptance.

## Audit Events (all from AUDIT_EVENTS.md, actor RECOMMENDER unless noted)

`REFERENCE_REQUEST_OPENED`, `RECOMMENDER_EMAIL_CONFIRMED`, `INVITATION_TOKEN_CONSUMED`,
`CONSENT_GRANTED`, `CONSENT_DECLINED`, `REQUEST_DECLINED`, `REFERENCE_RESPONSE_STARTED`,
`REFERENCE_RESPONSE_SUBMITTED`, `RECIPIENT_CONFIRMED_BY_RECOMMENDER`,
`RELATIONSHIP_CONFIRMED_BY_RECOMMENDER`, `INVITATION_TOKEN_REVOKED` (SYSTEM, on decline).
Metadata: IDs, statuses, consent types/versions/region, decline reason — never emails or
names.

## Testing

- Unit: code attempt limiting; masking helper.
- Integration (Testcontainers): full happy path open→code→confirm→consent→draft→submit→
  NEEDS_REVIEW with all audit events; consumed/expired/revoked token on GET → 404;
  wrong code ×5 then correct code → still rejected; new code works; decline at consent
  gate (consent DECLINED record); one-click decline pre- and post-confirmation;
  report-abuse metadata; draft before consent → 409; submit without confirmations → 400;
  session of request A cannot act on request B (implicitly — actor bound to request);
  expired session → 401; requester-side send/cancel unaffected (existing suite).
- OpenAPI snapshot refreshed.

## Documentation Updates

- `DATA_MODEL.md`: implementation status; `ReferenceResponse.approved_letter_text`.
- `API_GUIDELINES.md`: invitation/recommender endpoint shapes aligned with
  AUTHENTICATION.md (session-scoped after confirmation).
- `docs/ROADMAP.md`: mark recommender flow delivered.
- `docs/agent/IMPLEMENTATION_HISTORY.md`: iteration 4 entry.

## Risks / Accepted Trade-offs

- PII erasure on decline is recorded (status + audit) but physically executed only when
  the privacy module ships.
- Cross-border consent necessity is decided client-side (jurisdiction of the recommender
  is not server-detectable); the backend records whatever explicit grant was given. The
  `local` dev cell requires only processing consent.
- Reminders/expiry unchanged (Temporal item); drafts share the request expiry when that
  lands.
- Session fixation not applicable (cookie minted only at confirmation, never rotated
  mid-flow; TTL 1h).
