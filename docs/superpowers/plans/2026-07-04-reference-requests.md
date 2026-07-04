# Reference Requests (Requester Side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Requester-side reference request lifecycle: create (with blocking verbal-consent attestation), send invitation email with a hashed single-use token, cancel, list, get.

**Architecture:** New `requests` module (owns `reference_request` + `consent_record`), `invitation_token` owned by `identity` with a new package-root public API, new read-only public APIs on `contacts` and `templates`. No Temporal. Spec: `docs/superpowers/specs/2026-07-04-reference-requests-design.md`.

**Tech Stack:** Kotlin, Spring Boot 4, Spring Modulith, jOOQ (regenerated from Flyway), Testcontainers.

## Global Constraints

- Never log or store raw tokens; HMAC via existing `TokenHasher`.
- Audit metadata: IDs/statuses/consent metadata only — never contact names or emails.
- All endpoints owner-scoped by requester profile; existing session auth + CSRF.
- Error contract via `ApiException`; existing codes: `VALIDATION_ERROR`, `RATE_LIMITED`, `NOT_FOUND`. New: `CONSENT_REQUIRED`, `INVALID_REQUEST_STATE`.
- Canonical statuses/transitions per `docs/WORKFLOWS.md`; audit event names per `docs/AUDIT_EVENTS.md`.
- After any migration change run `./gradlew generateJooq`; never edit generated code.
- OpenAPI snapshot must be refreshed (`UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`).

---

### Task 1: Flyway V4 migration + configuration

**Files:**
- Create: `apps/backend/src/main/resources/db/migration/V4__reference_requests.sql`
- Modify: `apps/backend/src/main/kotlin/com/verifolio/platform/VerifolioProperties.kt`
- Modify: `apps/backend/src/main/resources/application.yaml`

**Interfaces:**
- Produces: tables `reference_request`, `consent_record`, `invitation_token`; config `props.consents.requesterAttestation.{textId,version}`, `props.requests.{expiry,sendLimitPerRecommender,sendLimitWindow}`.

- [ ] **Step 1: Write the migration**

```sql
create table reference_request (
    id                     uuid primary key default gen_random_uuid(),
    requester_profile_id   uuid not null references person_profile (id),
    recommender_contact_id uuid not null references recommender_contact (id) on delete restrict,
    template_id            uuid not null references template (id),
    purpose                text,
    status                 text not null default 'CREATED'
        check (status in ('CREATED','SENT','OPENED','IN_PROGRESS','SUBMITTED','NEEDS_REVIEW',
                          'CORRECTION_REQUESTED','COMPLETED','DECLINED','EXPIRED','CANCELLED')),
    expires_at             timestamptz not null,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);
create index idx_reference_request_owner on reference_request (requester_profile_id, created_at, id);

create table consent_record (
    id                     uuid primary key default gen_random_uuid(),
    subject_type           text not null check (subject_type in ('REQUESTER','RECOMMENDER')),
    user_id                uuid references user_account (id),
    recommender_contact_id uuid references recommender_contact (id),
    reference_request_id   uuid references reference_request (id) on delete restrict,
    consent_type           text not null check (consent_type in (
        'REQUESTER_VERBAL_CONSENT_ATTESTATION','RECOMMENDER_PROCESSING_CONSENT',
        'RECOMMENDER_PUBLIC_SHARING_CONSENT','CROSS_BORDER_TRANSFER_CONSENT')),
    policy_text_version    text not null,
    region                 text not null,
    status                 text not null check (status in ('GRANTED','DECLINED','WITHDRAWN')),
    granted_at             timestamptz,
    declined_at            timestamptz,
    withdrawn_at           timestamptz,
    created_at             timestamptz not null default now(),
    constraint chk_consent_subject check (
        (subject_type = 'REQUESTER' and user_id is not null and recommender_contact_id is null) or
        (subject_type = 'RECOMMENDER' and recommender_contact_id is not null and user_id is null)
    )
);
create index idx_consent_record_request on consent_record (reference_request_id);

create table invitation_token (
    id                uuid primary key default gen_random_uuid(),
    request_id        uuid not null references reference_request (id),
    recommender_email text not null,
    token_hash        text not null unique,
    expires_at        timestamptz not null,
    consumed_at       timestamptz,
    revoked_at        timestamptz,
    created_at        timestamptz not null default now()
);
create index idx_invitation_token_request on invitation_token (request_id);
```

- [ ] **Step 2: Extend `VerifolioProperties`** — add fields to the data class:

```kotlin
val consents: Consents = Consents(),
val requests: Requests = Requests(),
```

and nested types:

```kotlin
data class Consents(
    val requesterAttestation: ConsentText = ConsentText(textId = "local-requester-attestation", version = 1),
)

data class ConsentText(val textId: String, val version: Int) {
    /** Stored in ConsentRecord.policy_text_version, e.g. "local-requester-attestation:1". */
    val versionedId: String get() = "$textId:$version"
}

data class Requests(
    val expiry: Duration = Duration.ofDays(21),
    val sendLimitPerRecommender: Int = 5,
    val sendLimitWindow: Duration = Duration.ofDays(1),
)
```

- [ ] **Step 3: application.yaml** — under `verifolio:` add:

```yaml
  consents:
    requester-attestation:
      text-id: local-requester-attestation # region policy text id; per-cell override in deployment config
      version: 1
  requests:
    expiry: 21d
    send-limit-per-recommender: 5 # global anti-spam limit across all requesters (RECOMMENDER_EXPERIENCE.md)
    send-limit-window: 1d
```

- [ ] **Step 4: Regenerate jOOQ + compile**

Run: `cd apps/backend && ./gradlew generateJooq compileKotlin`
Expected: BUILD SUCCESSFUL; generated classes include `REFERENCE_REQUEST`, `CONSENT_RECORD`, `INVITATION_TOKEN`.

- [ ] **Step 5: Commit** — `feat(backend): V4 migration and config for reference requests`

---

### Task 2: Move `SlidingWindowRateLimiter` to platform

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/platform/SlidingWindowRateLimiter.kt` (moved from `identity/infrastructure`, same code, `package com.verifolio.platform`)
- Delete: `apps/backend/src/main/kotlin/com/verifolio/identity/infrastructure/SlidingWindowRateLimiter.kt`
- Modify: `identity/infrastructure/IdentityBeans.kt`, `identity/application/MagicLinkService.kt` (imports only)

**Interfaces:**
- Produces: `com.verifolio.platform.SlidingWindowRateLimiter(limit: Int, window: Duration)` with `tryAcquire(key: String): Boolean` — used by Task 6.

- [ ] **Step 1: Move the class, update imports** (technical infra, no domain logic — allowed in platform; identity beans keep the same bean names)
- [ ] **Step 2: Run** `./gradlew test --tests "*RateLimit*" --tests "*Modularity*"` — Expected: PASS
- [ ] **Step 3: Commit** — `refactor(backend): promote SlidingWindowRateLimiter to platform`

---

### Task 3: Public lookup APIs on contacts and templates

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/contacts/ContactLookup.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/contacts/application/ContactLookupImpl.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/templates/TemplateLookup.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/templates/application/TemplateLookupImpl.kt`

**Interfaces (produces):**

```kotlin
package com.verifolio.contacts

data class ContactSnapshot(val id: UUID, val name: String, val email: String)

/** Public API of the contacts module for cross-module reads. */
interface ContactLookup {
    /** Returns the contact only when it belongs to [ownerProfileId]. */
    fun findOwned(contactId: UUID, ownerProfileId: UUID): ContactSnapshot?
}
```

```kotlin
package com.verifolio.templates

/** Public API of the templates module for cross-module reads. */
interface TemplateLookup {
    fun exists(templateId: UUID): Boolean
}
```

- [ ] **Step 1: Implement both** (impls are `internal @Service` classes in `application/` using `DSLContext` on their own module's table)

```kotlin
package com.verifolio.contacts.application

import com.verifolio.contacts.ContactLookup
import com.verifolio.contacts.ContactSnapshot
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class ContactLookupImpl(private val dsl: DSLContext) : ContactLookup {
    override fun findOwned(contactId: UUID, ownerProfileId: UUID): ContactSnapshot? {
        val rc = RECOMMENDER_CONTACT
        return dsl.select(rc.ID, rc.NAME, rc.EMAIL).from(rc)
            .where(rc.ID.eq(contactId).and(rc.OWNER_PROFILE_ID.eq(ownerProfileId)))
            .fetchOne()
            ?.let { ContactSnapshot(it[rc.ID]!!, it[rc.NAME]!!, it[rc.EMAIL]!!) }
    }
}
```

```kotlin
package com.verifolio.templates.application

import com.verifolio.jooq.tables.references.TEMPLATE
import com.verifolio.templates.TemplateLookup
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class TemplateLookupImpl(private val dsl: DSLContext) : TemplateLookup {
    override fun exists(templateId: UUID): Boolean =
        dsl.fetchExists(dsl.selectFrom(TEMPLATE).where(TEMPLATE.ID.eq(templateId)))
}
```

- [ ] **Step 2: Compile** — `./gradlew compileKotlin` — Expected: BUILD SUCCESSFUL (behaviour covered by Task 5 integration tests)
- [ ] **Step 3: Commit** — `feat(backend): ContactLookup and TemplateLookup public module APIs`

---

### Task 4: Identity invitation tokens

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/InvitationTokenService.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/application/InvitationTokenServiceImpl.kt`

**Interfaces (produces):**

```kotlin
package com.verifolio.identity

import java.time.Duration
import java.util.UUID

/** Public API: single-use recommender invitation tokens (AUTHENTICATION.md). */
interface InvitationTokenService {
    /** Mints a token bound to the request; returns the RAW token (DB stores only the HMAC hash). */
    fun mint(requestId: UUID, recommenderEmail: String, ttl: Duration): String

    /** Revokes all outstanding (unconsumed, unrevoked) tokens for the request. Audited per token. */
    fun revokeForRequest(requestId: UUID): Int
}
```

- [ ] **Step 1: Implement**

```kotlin
package com.verifolio.identity.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.InvitationTokenService
import com.verifolio.identity.domain.TokenGenerator
import com.verifolio.identity.domain.TokenHasher
import com.verifolio.jooq.tables.references.INVITATION_TOKEN
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
internal class InvitationTokenServiceImpl(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val audit: AuditService,
) : InvitationTokenService {

    @Transactional
    override fun mint(requestId: UUID, recommenderEmail: String, ttl: Duration): String {
        val raw = TokenGenerator.generate()
        val it = INVITATION_TOKEN
        dsl.insertInto(it)
            .set(it.REQUEST_ID, requestId)
            .set(it.RECOMMENDER_EMAIL, recommenderEmail)
            .set(it.TOKEN_HASH, hasher.hash(raw))
            .set(it.EXPIRES_AT, OffsetDateTime.now().plus(ttl))
            .execute()
        return raw
    }

    @Transactional
    override fun revokeForRequest(requestId: UUID): Int {
        val it = INVITATION_TOKEN
        val revoked = dsl.update(it)
            .set(it.REVOKED_AT, OffsetDateTime.now())
            .where(it.REQUEST_ID.eq(requestId).and(it.CONSUMED_AT.isNull).and(it.REVOKED_AT.isNull))
            .returning(it.ID)
            .fetch()
        revoked.forEach { row ->
            audit.record(
                actorType = "SYSTEM", actorId = null, action = "INVITATION_TOKEN_REVOKED",
                entityType = "INVITATION_TOKEN", entityId = row.id.toString(),
                metadata = mapOf("requestId" to requestId.toString()),
            )
        }
        return revoked.size
    }
}
```

- [ ] **Step 2: Compile** — behaviour asserted in Task 6/7 integration tests (send stores hash ≠ raw; cancel revokes)
- [ ] **Step 3: Commit** — `feat(backend): invitation token minting and revocation in identity`

---

### Task 5: Requests module — domain + create + get/list

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/domain/ReferenceRequestStatus.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/api/ReferenceRequestDtos.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/api/ReferenceRequestController.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/application/ReferenceRequestService.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/requests/domain/ReferenceRequestStatusTest.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/requests/ReferenceRequestIntegrationTest.kt`

**Interfaces:**
- Consumes: `ProfileService.requireProfileId`, `ContactLookup.findOwned`, `TemplateLookup.exists`, `AuditService.record`, `VerifolioProperties`.
- Produces: `ReferenceRequestService.create/get/list`, DTOs below; `send`/`cancel` added in Tasks 6–7.

- [ ] **Step 1: Domain enum + failing unit test**

```kotlin
package com.verifolio.requests.domain

/** Canonical state machine (docs/WORKFLOWS.md status transition table). */
enum class ReferenceRequestStatus {
    CREATED, SENT, OPENED, IN_PROGRESS, SUBMITTED, NEEDS_REVIEW,
    CORRECTION_REQUESTED, COMPLETED, DECLINED, EXPIRED, CANCELLED;

    val terminal: Boolean
        get() = this == COMPLETED || this == DECLINED || this == EXPIRED || this == CANCELLED

    fun canTransitionTo(target: ReferenceRequestStatus): Boolean = when (target) {
        CREATED -> false
        SENT -> this == CREATED
        OPENED -> this == SENT
        IN_PROGRESS -> this == OPENED || this == CORRECTION_REQUESTED
        SUBMITTED -> this == IN_PROGRESS
        NEEDS_REVIEW -> this == SUBMITTED
        CORRECTION_REQUESTED -> this == NEEDS_REVIEW
        COMPLETED -> this == NEEDS_REVIEW
        DECLINED -> this == SENT || this == OPENED || this == IN_PROGRESS
        EXPIRED -> this == SENT || this == OPENED || this == IN_PROGRESS || this == CORRECTION_REQUESTED
        CANCELLED -> !terminal
    }
}
```

Unit test asserts: happy chain CREATED→SENT→OPENED→IN_PROGRESS→SUBMITTED→NEEDS_REVIEW→COMPLETED; correction loop NEEDS_REVIEW→CORRECTION_REQUESTED→IN_PROGRESS; CANCELLED from every non-terminal, not from terminal; no transition out of COMPLETED/DECLINED/EXPIRED/CANCELLED; SENT only from CREATED.

- [ ] **Step 2: DTOs**

```kotlin
package com.verifolio.requests.api

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateReferenceRequestRequest(
    @field:NotNull val recommenderContactId: UUID?,
    @field:NotNull val templateId: UUID?,
    @field:Size(max = 2000) val purpose: String? = null,
    val verbalConsentAttested: Boolean = false,
)

data class ReferenceRequestResponse(
    val id: String,
    val recommenderContactId: String,
    val templateId: String,
    val purpose: String?,
    val status: String,
    val expiresAt: String,
    val createdAt: String,
    val updatedAt: String?,
)

data class ReferenceRequestListResponse(
    val items: List<ReferenceRequestResponse>,
    val nextCursor: String?,
)
```

- [ ] **Step 3: Service (create/get/list) + controller.** Create runs in one transaction: verify contact ownership (`404 NOT_FOUND "Contact not found"`), template existence (`400 VALIDATION_ERROR "Unknown template"`), `verbalConsentAttested == true` (`400 CONSENT_REQUIRED`), insert request (status CREATED, `expires_at = now + props.requests.expiry`), insert consent record (REQUESTER / caller's `user_id` / `REQUESTER_VERBAL_CONSENT_ATTESTATION` / GRANTED / `policy_text_version = props.consents.requesterAttestation.versionedId` / `region = props.region` / `granted_at = now` / `reference_request_id`), audit `REFERENCE_REQUEST_CREATED` (metadata: templateId, recommenderContactId) and `CONSENT_GRANTED` (metadata: consentType, policyTextVersion, region, referenceRequestId). List: keyset cursor identical to `ContactService` (Base64 `createdAt|id`, page size 50, lookahead), optional `status` query param validated against the enum (`400 VALIDATION_ERROR` on garbage). Get: owner-scoped, `404 NOT_FOUND "Reference request not found"`. Controller mirrors `ContactController` (`@RestController`, `/api/v1/reference-requests`, `@AuthenticationPrincipal AuthenticatedUser`, ApiResponses annotations incl. 401/403/404).

- [ ] **Step 4: Failing integration tests, then run** — create happy path asserts 201, `status == "CREATED"`, consent row in `CONSENT_RECORD` (subjectType REQUESTER, userId set, contact id null, requestId set, status GRANTED), audit actions contain both events; `verbalConsentAttested=false` → 400 `CONSENT_REQUIRED` and no rows; another user's contact → 404; random templateId → 400; list pagination + owner isolation (user B sees empty list); status filter; get 404 for foreign id. Reuse the `login`/`xsrf`/`authHeaders` helpers pattern from `ContactIntegrationTest`; template id fetched via `GET /api/v1/templates?locale=en`; contact created via `POST /api/v1/contacts`.

Run: `./gradlew test --tests "*ReferenceRequest*"`
Expected: PASS

- [ ] **Step 5: Commit** — `feat(backend): reference request create/get/list with verbal-consent attestation`

---

### Task 6: Send command

**Files:**
- Modify: `ReferenceRequestService.kt`, `ReferenceRequestController.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/requests/infrastructure/RequestsBeans.kt`
- Test: extend `ReferenceRequestIntegrationTest.kt`

**Interfaces:**
- Consumes: `InvitationTokenService.mint`, `MailPort.send`, `platform.SlidingWindowRateLimiter`, `props.auth.frontendBaseUrl`.

- [ ] **Step 1: Bean**

```kotlin
package com.verifolio.requests.infrastructure

import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.VerifolioProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class RequestsBeans {
    /** Global per-recommender-email limit across ALL requesters (RECOMMENDER_EXPERIENCE.md anti-spam). */
    @Bean("referenceRequestSendLimiter")
    fun referenceRequestSendLimiter(props: VerifolioProperties) =
        SlidingWindowRateLimiter(props.requests.sendLimitPerRecommender, props.requests.sendLimitWindow)
}
```

- [ ] **Step 2: `send(user, id)` in service** (single transaction): load owned request else 404; require status `CREATED` else `409 INVALID_REQUEST_STATE`; require `expires_at > now` else `409 INVALID_REQUEST_STATE "Request has expired"`; defense-in-depth: GRANTED `REQUESTER_VERBAL_CONSENT_ATTESTATION` consent row for this request exists else `409 CONSENT_REQUIRED`; resolve contact snapshot (`ContactLookup.findOwned`, 404 if gone); `limiter.tryAcquire(contact.email.lowercase())` else `429 RATE_LIMITED`; `mint(requestId, contact.email, ttl = Duration.between(now, expiresAt))`; email via `MailPort` — subject `"Reference request from ${user.email}"`, body includes purpose (if any), main link `${props.auth.frontendBaseUrl}/invitations/$raw`, decline link `${...}/invitations/$raw/decline`, report-abuse link `${...}/invitations/$raw/report-abuse`; update status → SENT + `updated_at`; audit `REFERENCE_REQUEST_SENT`. Controller: `POST /{id}/send` → 200 with DTO.

- [ ] **Step 3: Integration tests** — happy path (mail recorded to contact email; body contains `/invitations/`; raw token from body is NOT in `invitation_token.token_hash` column but a row exists for the request; status SENT; audit `REFERENCE_REQUEST_SENT`); double send → 409 `INVALID_REQUEST_STATE`; rate limit: with limit exhausted for that email → 429 (create 5 requests to same contact, send all, 6th → 429); foreign request → 404.

Run: `./gradlew test --tests "*ReferenceRequest*"` — Expected: PASS

- [ ] **Step 4: Commit** — `feat(backend): send reference request — invitation token, email, rate limit`

---

### Task 7: Cancel command

**Files:**
- Modify: `ReferenceRequestService.kt`, `ReferenceRequestController.kt`
- Test: extend `ReferenceRequestIntegrationTest.kt`

- [ ] **Step 1: `cancel(user, id)`**: load owned else 404; current status must satisfy `canTransitionTo(CANCELLED)` else `409 INVALID_REQUEST_STATE`; update status → CANCELLED + `updated_at`; `invitationTokenService.revokeForRequest(id)`; audit `REFERENCE_REQUEST_CANCELLED` (metadata: previousStatus). Controller: `POST /{id}/cancel` → 200 with DTO.
- [ ] **Step 2: Integration tests** — cancel from CREATED (200, status CANCELLED, audit event); cancel after send → invitation token row has `revoked_at` set + `INVITATION_TOKEN_REVOKED` audit; double cancel → 409; send after cancel → 409.

Run: `./gradlew test --tests "*ReferenceRequest*"` — Expected: PASS

- [ ] **Step 3: Commit** — `feat(backend): cancel reference request with invitation token revocation`

---

### Task 8: DB constraint test + full suite

**Files:**
- Test: extend `ReferenceRequestIntegrationTest.kt` (or a small `ConsentRecordConstraintTest`)

- [ ] **Step 1: Constraint tests** — raw jOOQ inserts into `CONSENT_RECORD`: both `user_id` and `recommender_contact_id` null → throws; both set → throws (assert `DataAccessException` mentioning `chk_consent_subject`).
- [ ] **Step 2: Full suite** — Run: `./gradlew test` — Expected: all green including `ModularityTests` (new cross-module edges use package-root public APIs only).
- [ ] **Step 3: Commit** — `test(backend): consent record subject-attribution DB constraints`

---

### Task 9: OpenAPI snapshot + docs

**Files:**
- Modify: `apps/backend/api/openapi.yaml` (generated refresh)
- Modify: `docs/DATA_MODEL.md` (implementation status; add `reference_request_id` to ConsentRecord with attribution note)
- Modify: `docs/ROADMAP.md` (mark "Reference requests… — requester side delivered (2026-07, apps/backend); recommender flow pending")
- Modify: `docs/agent/IMPLEMENTATION_HISTORY.md` (iteration 3 entry: what shipped, conventions, deferred items — recommender endpoints, stop-reminders/report-abuse backend, EXPIRED transition, Temporal)

- [ ] **Step 1: Refresh snapshot** — Run: `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"` — Expected: PASS, snapshot updated with 5 new endpoints.
- [ ] **Step 2: Update the three docs.**
- [ ] **Step 3: Full build** — Run: `./gradlew build` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** — `docs(backend): OpenAPI snapshot + data model/roadmap/history for reference requests`

---

### Task 10: Push and PR

- [ ] **Step 1:** `git push -u origin feature/reference-requests`
- [ ] **Step 2:** `gh pr create` — title `feat(backend): reference requests (requester side) with consent model`; body: summary, spec/plan links, AGENTS.md PR checklist filled in, unresolved risks (frontend decline/report-abuse links pending recommender flow; in-process rate limiter; no EXPIRED auto-transition).
