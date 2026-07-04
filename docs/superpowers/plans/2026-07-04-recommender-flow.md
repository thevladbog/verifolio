# Recommender Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recommender-side backend: invitation open → one-time-code email confirmation → recommender session → consent gate → draft answers → submission (`NEEDS_REVIEW`), plus decline paths.

**Architecture:** identity owns tokens/codes/sessions and exposes `InvitationAccess`/`RecommenderSessions`/`RecommenderActor`; requests owns flow state, `reference_response`, consent records, and both controllers. Session cookie `verifolio_recommender_session` after single-use token consumption. Spec: `docs/superpowers/specs/2026-07-04-recommender-flow-design.md`.

**Tech Stack:** Kotlin, Spring Boot 4 + Spring Security, Spring Modulith, jOOQ, Testcontainers.

## Global Constraints

- Raw tokens/codes never stored or logged — HMAC via `TokenHasher`.
- Audit events from `docs/AUDIT_EVENTS.md`, actor `RECOMMENDER` (system revocations: `SYSTEM`); metadata without emails/names.
- Status transitions only through `ReferenceRequestStatus.canTransitionTo`.
- Error codes: existing + `CODE_INVALID` (400), `CONFIRMATION_REQUIRED` (400).
- `./gradlew generateJooq` after migration changes; OpenAPI snapshot refresh at the end.

---

### Task 1: Flyway V5 + configuration

**Files:**
- Create: `apps/backend/src/main/resources/db/migration/V5__recommender_flow.sql`
- Modify: `apps/backend/src/main/kotlin/com/verifolio/platform/VerifolioProperties.kt`
- Modify: `apps/backend/src/main/resources/application.yaml`

**Interfaces:** produces tables `recommender_session`, `email_confirmation_code`, `reference_response`; config `props.auth.{recommenderSessionTtl,emailConfirmationTtl,emailConfirmationLimit,emailConfirmationWindow}`, `props.consents.{processing,crossBorderTransfer}`.

- [ ] **Step 1: Migration**

```sql
create table recommender_session (
    id                uuid primary key default gen_random_uuid(),
    request_id        uuid not null references reference_request (id),
    recommender_email text not null,
    token_hash        text not null unique,
    ip_hash           text,
    user_agent_hash   text,
    expires_at        timestamptz not null,
    revoked_at        timestamptz,
    created_at        timestamptz not null default now()
);
create index idx_recommender_session_request on recommender_session (request_id);

create table email_confirmation_code (
    id                  uuid primary key default gen_random_uuid(),
    invitation_token_id uuid not null references invitation_token (id),
    code_hash           text not null,
    expires_at          timestamptz not null,
    consumed_at         timestamptz,
    attempts            int not null default 0,
    created_at          timestamptz not null default now()
);
create index idx_email_confirmation_code_token on email_confirmation_code (invitation_token_id);

create table reference_response (
    id                     uuid primary key default gen_random_uuid(),
    request_id             uuid not null references reference_request (id),
    recommender_email      text not null,
    answers_json           jsonb not null default '{}'::jsonb,
    approved_letter_text   text,
    confirmation_text      text,
    relationship_confirmed boolean not null default false,
    recipient_confirmed    boolean not null default false,
    submitted_at           timestamptz,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);
-- one draft (unsubmitted row) per request; correction cycles create new rows later
create unique index uq_reference_response_draft on reference_response (request_id) where submitted_at is null;
```

- [ ] **Step 2: Properties** — `Auth` gains `recommenderSessionTtl: Duration = Duration.ofHours(1)`, `emailConfirmationTtl: Duration = Duration.ofMinutes(10)`, `emailConfirmationLimit: Int = 3`, `emailConfirmationWindow: Duration = Duration.ofMinutes(15)`; `Consents` gains `processing: ConsentText = ConsentText("local-processing", 1)` and `crossBorderTransfer: ConsentText = ConsentText("local-cross-border", 1)`. application.yaml mirrors the values with comments.
- [ ] **Step 3:** `./gradlew generateJooq compileKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** — `feat(backend): V5 migration and config for recommender flow`

---

### Task 2: identity — invitation access, codes, recommender sessions, filter

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/RecommenderAccess.kt` (public API)
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/application/InvitationAccessImpl.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/application/RecommenderSessionsImpl.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/api/RecommenderSessionAuthFilter.kt`
- Modify: `apps/backend/src/main/kotlin/com/verifolio/identity/api/SecurityConfig.kt`
- Modify: `apps/backend/src/main/kotlin/com/verifolio/identity/infrastructure/IdentityBeans.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/identity/InvitationAccessIntegrationTest.kt` (code attempts, expiry)

**Interfaces (produces):**

```kotlin
package com.verifolio.identity

data class InvitationInfo(val requestId: UUID, val recommenderEmail: String)
data class RecommenderGrant(val rawSessionToken: String, val requestId: UUID, val recommenderEmail: String)
data class RecommenderActor(val requestId: UUID, val email: String)

interface InvitationAccess {
    fun peek(rawToken: String): InvitationInfo?                       // valid & unconsumed only
    fun identify(rawToken: String): InvitationInfo?                   // hash lookup regardless of state (decline paths)
    fun issueEmailConfirmation(rawToken: String): String              // returns RAW code; RATE_LIMITED over limit
    fun confirmEmail(rawToken: String, code: String, ipHash: String?, userAgentHash: String?): RecommenderGrant
}

interface RecommenderSessions {
    fun resolve(rawSessionToken: String): RecommenderActor?
    fun revokeForRequest(requestId: UUID): Int
}
```

- [ ] **Step 1: Implement** — `InvitationAccessImpl`: peek/identify select by `hasher.hash(raw)`; issue: peek-valid else 404, `SlidingWindowRateLimiter` bean `emailConfirmationLimiter` keyed by token id (limit/window from props), 6-digit code via `SecureRandom.nextInt(1_000_000)` zero-padded, row inserted hashed, TTL `props.auth.emailConfirmationTtl`; confirm: latest unconsumed code for token — expired/`attempts >= 5`/hash-mismatch (attempts++) → `ApiException(400, "CODE_INVALID", ...)`; success → code+token consumed, `recommender_session` row minted (TokenGenerator, TTL `recommenderSessionTtl`), audits `RECOMMENDER_EMAIL_CONFIRMED` + `INVITATION_TOKEN_CONSUMED` (actor RECOMMENDER, entity INVITATION_TOKEN/REFERENCE_REQUEST ids only). `RecommenderSessionsImpl.resolve`: hash lookup, expires/revoked checks. Cookie name constant `RecommenderSessionCookie.NAME = "verifolio_recommender_session"` in identity.api (public via filter usage in requests? controllers only need the name — export it in the public API file as `const val RECOMMENDER_SESSION_COOKIE = "verifolio_recommender_session"` in `RecommenderAccess.kt`).
- [ ] **Step 2: Filter + security** — `RecommenderSessionAuthFilter` mirrors `SessionAuthFilter` for the recommender cookie setting `RecommenderActor` principal; register `addFilterBefore(recommenderSessionAuthFilter, CsrfFilter::class.java)` after the user filter. SecurityConfig: `permitAll` + CSRF-ignore for `/api/v1/invitations/**`; `/api/v1/recommender/**` stays behind `authenticated()`.
- [ ] **Step 3: Integration test** — wrong code 5× then correct code still `CODE_INVALID`; fresh code succeeds; expired code rejected (insert with past expiry via DSLContext).
- [ ] **Step 4:** `./gradlew test --tests "*InvitationAccess*"` → PASS. Commit — `feat(backend): recommender sessions, email confirmation codes, invitation access API`

---

### Task 3: profiles displayName + templates snapshot

**Files:**
- Modify: `apps/backend/src/main/kotlin/com/verifolio/profiles/ProfileService.kt` (+ impl in `profiles/application/ProfileApplicationService.kt`)
- Modify: `apps/backend/src/main/kotlin/com/verifolio/templates/TemplateLookup.kt` (+ `templates/application/TemplateLookupImpl.kt`)

**Interfaces (produces):**

```kotlin
// profiles
fun displayName(profileId: UUID): String?

// templates
data class TemplateSnapshot(val id: UUID, val name: String, val questionSchemaJson: String)
fun snapshot(templateId: UUID): TemplateSnapshot?    // added to TemplateLookup
```

- [ ] **Step 1: Implement both; compile.** Commit — `feat(backend): profile displayName and template snapshot lookups`

---

### Task 4: requests — invitation endpoints (open, codes, confirm, decline)

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/api/InvitationController.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/api/InvitationDtos.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/application/RecommenderFlowService.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/requests/RecommenderFlowIntegrationTest.kt`

**Interfaces:**
- Consumes: `InvitationAccess`, `RecommenderSessions`, `ProfileService.displayName`, `TemplateLookup.snapshot`, `MailPort`, `AuditService`, `ReferenceRequestStatus`.
- Produces: `RecommenderFlowService.open(rawToken)`, `.requestCode(rawToken)`, `.confirm(rawToken, code, ipHash, uaHash): RecommenderGrant`, `.declineByToken(rawToken, reason)`; DTOs `InvitationPreviewResponse(requesterName, purpose, templateName, recommenderEmailMasked, status)`, `ConfirmEmailRequest(code)`.

- [ ] **Step 1: Endpoints**
  - `GET /api/v1/invitations/{token}` — `peek` → 404 `NOT_FOUND` if null; load request row (guard: terminal → 404); if `SENT` → transition `OPENED` + audit `REFERENCE_REQUEST_OPENED` (actor RECOMMENDER); preview DTO with `maskEmail("jane@corp.com") == "j***@corp.com"` helper.
  - `POST /{token}/email-confirmations` → 202; raw code emailed: subject `"Your Verifolio confirmation code"`, body contains `Code: <code>` (test regex `Code: (\d{6})`).
  - `POST /{token}/confirm-email` `{code}` → grant; `ResponseCookie` `verifolio_recommender_session`, HttpOnly, `secure=props.auth.cookieSecure`, SameSite Strict, path `/`, maxAge `recommenderSessionTtl`; body `{status}`.
  - `POST /{token}/decline` and `POST /{token}/report-abuse` — `identify` (works post-consumption) → 404 if unknown; request non-terminal else 409 `INVALID_REQUEST_STATE`; transition to `DECLINED` (via canTransitionTo from SENT/OPENED/IN_PROGRESS), `invitationTokens.revokeForRequest`, `recommenderSessions.revokeForRequest`, audit `REQUEST_DECLINED` metadata `reason = "declined" | "abuse_report"` (+`previousStatus`).
- [ ] **Step 2: Integration tests** — open flips SENT→OPENED once (second GET stays OPENED); invalid token → 404; code email arrives; confirm sets cookie; consumed token on GET → 404 but decline still works; one-click decline pre-confirmation; report-abuse metadata reason; declined request GET → 404.
- [ ] **Step 3:** `./gradlew test --tests "*RecommenderFlow*"` → PASS. Commit — `feat(backend): invitation open, email confirmation, one-click decline endpoints`

---

### Task 5: requests — session-scoped consent gate, draft, submission

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/api/RecommenderFlowController.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/api/RecommenderFlowDtos.kt`
- Modify: `apps/backend/src/main/kotlin/com/verifolio/requests/application/RecommenderFlowService.kt`
- Test: extend `RecommenderFlowIntegrationTest.kt`

**Interfaces:**
- Produces DTOs: `RecommenderRequestContext(status, requesterName, purpose, templateName, questionSchema, consents: ConsentTextsDto, draft: DraftDto?)`, `ConsentTextsDto(processing: ConsentTextRef, crossBorderTransfer: ConsentTextRef)`, `ConsentTextRef(textId, version)`, `ConsentDecisionRequest(accepted: Boolean, crossBorderAccepted: Boolean? = null)`, `DraftRequest(answersJson: Map<String, Any?>, approvedLetterText: String?)`, `SubmitResponseRequest(approvedLetterText, confirmationText, recipientConfirmed: Boolean, relationshipConfirmed: Boolean, answersJson: Map<String, Any?>? = null)`.
- Controller methods take `@AuthenticationPrincipal actor: RecommenderActor?`; `actor ?: throw ApiException(403, "FORBIDDEN", "Recommender session required")`.

- [ ] **Step 1: Service methods**
  - `context(actor)` — request row by `actor.requestId`; consent texts from props; draft row if any (answers parsed back to object).
  - `consent(actor, decision)` — status must be `OPENED` (409). `accepted=true`: insert `RECOMMENDER_PROCESSING_CONSENT` GRANTED (subject RECOMMENDER, `recommender_contact_id` from request, `policy_text_version = props.consents.processing.versionedId`, region, `reference_request_id`), optional `CROSS_BORDER_TRANSFER_CONSENT` when `crossBorderAccepted == true`; status → `IN_PROGRESS`; audit `CONSENT_GRANTED` per record. `accepted=false`: insert processing consent DECLINED (`declined_at`), status → `DECLINED`, audit `CONSENT_DECLINED` + `REQUEST_DECLINED` (reason `consent_declined`), revoke sessions + tokens.
  - `saveDraft(actor, draft)` — status `IN_PROGRESS` (409); upsert the `submitted_at IS NULL` row (insert … on conflict via the partial unique index → jOOQ `insertInto(..).onConflict(..).where(..)` is awkward; select-then-insert/update inside the transaction is fine); first insert audits `REFERENCE_RESPONSE_STARTED`.
  - `submit(actor, req)` — status `IN_PROGRESS` (409); GRANTED processing consent for the request exists (409 `CONSENT_REQUIRED`); `recipientConfirmed && relationshipConfirmed` else 400 `CONFIRMATION_REQUIRED`; `approvedLetterText` non-blank else 400 `VALIDATION_ERROR`; finalize row (upsert draft or create; set letter/confirmations/`submitted_at`); transitions `IN_PROGRESS→SUBMITTED→NEEDS_REVIEW` (assert both via canTransitionTo, store final `NEEDS_REVIEW`); audits `REFERENCE_RESPONSE_SUBMITTED`, `RECIPIENT_CONFIRMED_BY_RECOMMENDER`, `RELATIONSHIP_CONFIRMED_BY_RECOMMENDER`; `recommenderSessions.revokeForRequest`.
- [ ] **Step 2: Controller** — `GET /api/v1/recommender/request`, `POST /consent`, `PUT /response-draft`, `POST /responses` (201). CSRF applies (session cookie); tests must fetch XSRF token with the recommender cookie.
- [ ] **Step 3: Integration tests** — full happy path (open→code→confirm→consent→draft→submit) asserting final status `NEEDS_REVIEW`, response row (letter text, confirmations, submitted_at), audit actions all present; consent decline path (consent record DECLINED + request DECLINED + session revoked → next call 401); draft before consent → 409; submit without confirmations → 400 `CONFIRMATION_REQUIRED`; submit twice → 409; user-session cookie on recommender endpoint → 403; expired recommender session → 401 (insert expired session directly).
- [ ] **Step 4:** `./gradlew test --tests "*RecommenderFlow*"` → PASS. Commit — `feat(backend): recommender consent gate, response drafts, submission`

---

### Task 6: OpenAPI + docs + full suite

**Files:**
- Modify: `apps/backend/api/openapi.yaml` (refresh), `docs/DATA_MODEL.md`, `docs/API_GUIDELINES.md`, `docs/ROADMAP.md`, `docs/agent/IMPLEMENTATION_HISTORY.md`

- [ ] **Step 1:** `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"` → snapshot updated.
- [ ] **Step 2: Docs** — DATA_MODEL: implementation status + `ReferenceResponse.approved_letter_text`; API_GUIDELINES: replace token-scoped consent/response sketch with session-scoped `/api/v1/recommender/...` (note referencing AUTHENTICATION.md single-use rule); ROADMAP: recommender flow delivered; IMPLEMENTATION_HISTORY: iteration 4 entry with deferred items (PII erasure execution, cross-border client-decided, reminders/expiry, uploads/AI).
- [ ] **Step 3:** `./gradlew test --rerun-tasks -x generateJooq` → all green; verify count from `build/test-results`.
- [ ] **Step 4: Commit** — `docs(backend): OpenAPI + docs for recommender flow`

---

### Task 7: Push and PR

- [ ] `git push -u origin feature/recommender-flow`; `gh pr create` with summary, spec/plan links, AGENTS.md checklist, unresolved risks.
