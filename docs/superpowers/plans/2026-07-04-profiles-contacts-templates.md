# Profiles + Contacts + Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver person profiles (auto-created at first login), owner-scoped recommender contacts CRUD, read-only seeded templates, and a minimal organization entity in `apps/backend`.

**Architecture:** Same modular monolith as iteration 1. New code follows the established conventions in `docs/agent/IMPLEMENTATION_HISTORY.md`: module public API at `com.verifolio.<module>` package root, internals in `api/application/domain/infrastructure` subpackages marked `internal`; every sensitive action calls `AuditService.record` ; errors via `ApiException`/`ApiError`; integration tests extend `testsupport.IntegrationTest` (+ `RecordingMailConfig` where login flows are used). Identity→profiles link is event-driven (Spring Modulith `@ApplicationModuleListener`).

**Tech Stack:** unchanged (Kotlin, Spring Boot 3.5, Modulith, jOOQ, Flyway, Testcontainers).

**Environment:** run Gradle from `apps/backend` with `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home`; Docker required.

---

## Tasks

### Task 1: V2 migration + jOOQ regeneration

**Files:**
- Create: `apps/backend/src/main/resources/db/migration/V2__profiles_contacts_templates.sql`

- [ ] **Step 1: Write the migration**

```sql
create table person_profile (
    id                          uuid primary key default gen_random_uuid(),
    user_account_id             uuid not null unique references user_account (id),
    display_name                text not null,
    legal_name                  text,
    preferred_locale            text not null default 'en',
    profile_verification_status text not null default 'UNVERIFIED',
    created_at                  timestamptz not null default now(),
    updated_at                  timestamptz not null default now()
);

create table organization (
    id                  uuid primary key default gen_random_uuid(),
    name                text not null,
    domains             jsonb not null default '[]'::jsonb,
    verification_status text not null default 'UNVERIFIED',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create table recommender_contact (
    id                uuid primary key default gen_random_uuid(),
    owner_profile_id  uuid not null references person_profile (id),
    organization_id   uuid references organization (id),
    name              text not null,
    email             text not null,
    company_name      text,
    company_domain    text,
    title             text,
    relationship_type text not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);
create index idx_recommender_contact_owner on recommender_contact (owner_profile_id, created_at, id);

create table template (
    id                                uuid primary key default gen_random_uuid(),
    type                              text not null,
    locale                            text not null,
    name                              text not null,
    description                       text not null,
    question_schema_json              jsonb not null,
    output_schema_json                jsonb not null,
    required_fields_json              jsonb not null default '[]'::jsonb,
    verification_recommendations_json jsonb not null default '[]'::jsonb,
    created_at                        timestamptz not null default now(),
    updated_at                        timestamptz not null default now(),
    unique (type, locale)
);
```

- [ ] **Step 2:** `./gradlew generateJooq compileKotlin` → BUILD SUCCESSFUL; verify `com.verifolio.jooq.tables.references.{PERSON_PROFILE, ORGANIZATION, RECOMMENDER_CONTACT, TEMPLATE}` exist.
- [ ] **Step 3:** Commit `feat(backend): V2 schema for profiles, contacts, organizations, templates`.

---

### Task 2: V3 seed templates

**Files:**
- Create: `apps/backend/src/main/resources/db/migration/V3__seed_templates.sql`

- [ ] **Step 1: Build the seed migration.** Read `docs/REQUEST_TEMPLATES.md` and seed the SIX system templates (EMPLOYMENT_REFERENCE, IMMIGRATION_REFERENCE, VISA_SUPPORT_LETTER, ACADEMIC_RECOMMENDATION, CLIENT_TESTIMONIAL, CHARACTER_REFERENCE), locale `en`. JSON shapes:

`question_schema_json`:
```json
{
  "requesterQuestions": [
    {"key": "role", "label": "<question text>", "required": true, "publicDisplay": true}
  ],
  "recommenderQuestions": [
    {"key": "relationship", "label": "<question text>", "required": true, "publicDisplay": true}
  ]
}
```
Field keys are snake-free lowerCamel, derived from the doc's question meaning. Questions the doc marks "not for public display" (Immigration: company contact info; Visa: contact person) get `"publicDisplay": false`.

`output_schema_json`:
```json
{"sections": [{"key": "introduction", "title": "Introduction"}, ...]}
```
Derive sections from the doc's output_sections list per template.

`required_fields_json`: array of required question keys.
`verification_recommendations_json`: array of signal names exactly as in the doc, e.g. `["CORPORATE_DOMAIN_CONFIRMED", "RECOMMENDER_RELATIONSHIP_CONFIRMED", "RECIPIENT_CONFIRMED"]` (map the doc's prose to canonical signal names from docs/VERIFICATION_SIGNALS.md; letterhead scan → SCAN_ATTACHED, signature → SIGNATURE_ATTACHED / SIGNATURE_VERIFIED).

Use `insert into template (type, locale, name, description, question_schema_json, output_schema_json, required_fields_json, verification_recommendations_json) values (..., '...'::jsonb, ...)` — one statement per template, valid JSON (single-quoted SQL strings, escaped as needed).

- [ ] **Step 2:** `./gradlew generateJooq test --tests com.verifolio.ModularityTests` → migration applies cleanly (Flyway runs V3 in the codegen container).
- [ ] **Step 3:** Commit `feat(backend): seed six system templates (en)`.

---

### Task 3: Profiles module (event-driven auto-create + API)

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/UserAccountCreated.kt`
- Move: `AuthenticatedUser` from `identity/application/SessionService.kt` to `apps/backend/src/main/kotlin/com/verifolio/identity/AuthenticatedUser.kt` (module public API — profiles/contacts controllers need the principal type)
- Modify: `identity/application/SessionService.kt` (publish event on account creation)
- Create: `profiles/ProfileService.kt` (public API), `profiles/application/ProfileApplicationService.kt`, `profiles/application/UserAccountCreatedListener.kt`, `profiles/api/ProfileController.kt`, `profiles/api/ProfileDtos.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/profiles/ProfileIntegrationTest.kt`

- [ ] **Step 1: failing test** (TDD; login helper as in SessionIntegrationTest):

```kotlin
@Import(RecordingMailConfig::class)
class ProfileIntegrationTest : IntegrationTest() {
    // login(email): String — request magic link, extract token from RecordingMailPort, consume, return session cookie

    @Test
    fun `first login auto-creates a profile with defaults and audit event`() {
        val cookie = login("newuser@example.com")
        val profile = getJson("/api/v1/profile", cookie)          // 200
        assertThat(profile["displayName"]).isEqualTo("newuser")   // email local part
        assertThat(profile["preferredLocale"]).isEqualTo("en")
        assertThat(auditActions()).contains("PROFILE_CREATED")
    }

    @Test
    fun `second login does not create a second profile`() { /* login twice, one PERSON_PROFILE row */ }

    @Test
    fun `profile can be updated and update is audited`() {
        // PUT /api/v1/profile {displayName, legalName, preferredLocale} with CSRF header → 200
        // GET reflects changes; audit contains PROFILE_UPDATED
    }

    @Test
    fun `unauthenticated profile access is rejected`() { /* GET /api/v1/profile → 401 */ }
}
```
Write these as real tests following LogoutAndExpiryIntegrationTest's cookie/CSRF patterns.

- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: implement.**

`identity/UserAccountCreated.kt` (public API):
```kotlin
package com.verifolio.identity
import java.util.UUID
data class UserAccountCreated(val userAccountId: UUID, val email: String, val region: String)
```
In `SessionService.consumeMagicLink`, when the account is newly inserted (only then), publish via injected `ApplicationEventPublisher` inside the transaction.

`profiles/application/UserAccountCreatedListener.kt`:
```kotlin
@Component
internal class UserAccountCreatedListener(private val profiles: ProfileApplicationService) {
    @ApplicationModuleListener
    fun on(event: UserAccountCreated) = profiles.createIfAbsent(event.userAccountId, event.email)
}
```
`@ApplicationModuleListener` = async + after-commit + new transaction. `createIfAbsent` inserts with `onConflict(USER_ACCOUNT_ID).doNothing()`, display_name = email local part (`email.substringBefore("@")`), audits PROFILE_CREATED (actor SYSTEM, entityType USER_PROFILE, entityId profile id) only when a row was actually inserted.

IMPORTANT — test timing: the listener is async. In tests, poll (Awaitility-style loop with timeout ~5s, or `spring.modulith.events.*` sync config) before asserting the profile exists. Simplest reliable approach: after login, poll GET /api/v1/profile until 200 (max 5s). The GET endpoint itself must handle the not-yet-created window: respond 404 NOT_FOUND with code PROFILE_NOT_FOUND. Requires `implementation("org.springframework.modulith:spring-modulith-starter-jpa")`? NO — use `spring-modulith-events-api` only if needed; the starter-core already supports @ApplicationModuleListener but PERSISTENT event publication needs an event publication registry. If the registry requirement breaks the context, fall back to a plain `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` disabled (synchronous after-commit listener) — deterministic for tests, still boundary-clean. Choose the simplest configuration that works and report it.

`profiles/ProfileService.kt` (public API for other modules):
```kotlin
package com.verifolio.profiles
import java.util.UUID
interface ProfileService {
    fun requireProfileId(userAccountId: UUID): UUID // throws ApiException 404 PROFILE_NOT_FOUND
}
```
Implemented by `ProfileApplicationService` (internal).

`profiles/api/ProfileController.kt`: GET/PUT `/api/v1/profile`, principal `@AuthenticationPrincipal user: AuthenticatedUser` (now `com.verifolio.identity.AuthenticatedUser`). PUT validates displayName `@NotBlank`, preferredLocale in a small allowlist (`en`, `ru` — else VALIDATION_ERROR), audits PROFILE_UPDATED (actor USER). DTO `ProfileResponse(profileId, displayName, legalName, preferredLocale, profileVerificationStatus)`.

Update all `AuthenticatedUser` imports (SessionService, SessionAuthFilter, AuthController) after the move.

- [ ] **Step 4:** profile tests PASS; full `./gradlew test` green (ModularityTests confirm no identity→profiles compile dependency; the event class lives in identity's public API, profiles depends on identity — allowed direction).
- [ ] **Step 5:** Commit `feat(backend): profiles module with event-driven auto-creation`.

---

### Task 4: Contacts module (owner-scoped CRUD + pagination)

**Files:**
- Create: `contacts/api/ContactController.kt`, `contacts/api/ContactDtos.kt`, `contacts/application/ContactService.kt`, `contacts/domain/RelationshipType.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/contacts/ContactIntegrationTest.kt`

- [ ] **Step 1: failing tests** covering:
  - POST /api/v1/contacts (name+email+relationshipType required; invalid relationshipType → 400 VALIDATION_ERROR) → 201 with body; CONTACT_CREATED audited;
  - GET /api/v1/contacts/{id} returns own contact; user B (second login) gets 404 NOT_FOUND for user A's contact id;
  - PUT updates fields, audited CONTACT_UPDATED; DELETE → 204, row gone, CONTACT_DELETED audited; GET after delete → 404;
  - GET /api/v1/contacts pagination: create 55 contacts, first page 50 items + nextCursor, second page (cursor param) 5 items + null nextCursor, ordered by created_at,id.
  All mutating requests need the CSRF header dance (existing test pattern).

- [ ] **Step 2:** run → FAIL. **Step 3: implement.**

`RelationshipType` enum: MANAGER, COLLEAGUE, DIRECT_REPORT, CLIENT, PROFESSOR, MENTOR, PERSONAL, OTHER.

`ContactService` (internal): all queries filtered by `owner_profile_id = profiles.requireProfileId(user.userId)` (inject `com.verifolio.profiles.ProfileService`). Not-found and not-owned are indistinguishable: 404 NOT_FOUND. Keyset pagination: order by created_at, id; cursor = Base64("<createdAtEpochMicros>|<id>"); page size 50; invalid cursor → 400 VALIDATION_ERROR. organization_id NOT exposed in the API this iteration (internal column only).

DTOs: `ContactRequest(name, email, companyName?, companyDomain?, title?, relationshipType)` (validated), `ContactResponse(id, name, email, companyName, companyDomain, title, relationshipType, createdAt)`, `ContactListResponse(items: List<ContactResponse>, nextCursor: String?)`.

Audit: CONTACT_CREATED/UPDATED/DELETED — actor USER (account id), entityType RECOMMENDER_CONTACT, entityId contact id; metadata may include relationship_type but NEVER the contact's email/name (PII minimization).

- [ ] **Step 4:** tests PASS; full suite green. **Step 5:** Commit `feat(backend): contacts CRUD with ownership scoping and keyset pagination`.

---

### Task 5: Templates module (read-only API)

**Files:**
- Create: `templates/api/TemplateController.kt`, `templates/api/TemplateDtos.kt`, `templates/application/TemplateQueryService.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/templates/TemplateIntegrationTest.kt`

- [ ] **Step 1: failing tests**: GET /api/v1/templates returns the 6 seeded en templates (assert the 6 type names, authenticated request); `?locale=de` → empty list; GET /api/v1/templates/{id} returns full schema JSON fields; unknown id → 404 NOT_FOUND; unauthenticated → 401.
- [ ] **Step 2:** FAIL. **Step 3: implement.** Endpoints require authentication (default `anyRequest().authenticated()` — no SecurityConfig change). List: filter by locale param defaulting to `en`, ordered by type; no pagination. Response DTO: `TemplateResponse(id, type, locale, name, description, questionSchema, outputSchema, requiredFields, verificationRecommendations)` — jsonb fields deserialized to `JsonNode`/Map so the API returns real JSON objects, not strings. No audit for reads (rationale recorded in docs in Task 6).
- [ ] **Step 4:** tests PASS; full suite green. **Step 5:** Commit `feat(backend): read-only templates API`.

---

### Task 6: Contract + docs + agent data

**Files:**
- Modify: `apps/backend/api/openapi.yaml` (regenerate), `docs/AUDIT_EVENTS.md`, `docs/DATA_MODEL.md`, `docs/agent/IMPLEMENTATION_HISTORY.md`, `docs/ROADMAP.md`, `apps/backend/README.md`

- [ ] **Step 1:** Add OpenAPI `@ApiResponse` annotations on the three new controllers consistent with iteration 1 (success codes; 401/404/400 ApiError variants). Refresh snapshot: `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`, then run without env → PASS. Verify new paths/schemas present.
- [ ] **Step 2:** docs/AUDIT_EVENTS.md: add a "Profiles & Contacts" section with PROFILE_CREATED, PROFILE_UPDATED, CONTACT_CREATED, CONTACT_UPDATED, CONTACT_DELETED; add one sentence: template reads are not audited (no PII, no authorization boundary crossed).
- [ ] **Step 3:** docs/DATA_MODEL.md: add the RelationshipType enum values to RecommenderContact; extend the "Implementation Status" note (profiles/contacts/organizations/templates tables implemented in V2/V3).
- [ ] **Step 4:** docs/agent/IMPLEMENTATION_HISTORY.md: append "## 2026-07 — Profiles, contacts, templates" entry (what shipped, event-driven identity→profiles convention, keyset-cursor convention, seed-data-in-Flyway convention, deferred: organizations API, communication preferences, CUSTOM templates). docs/ROADMAP.md: mark profiles/contacts/templates delivered. apps/backend/README.md: update implemented-modules list.
- [ ] **Step 5:** `npx -y markdownlint-cli2 "**/*.md" "#node_modules" "#apps/backend/build"` → 0 errors; full `./gradlew test` green.
- [ ] **Step 6:** Commit `docs: profiles/contacts/templates contract and documentation`.

---

## Self-Review Notes

- Spec coverage: schema (T1), seed (T2), auto-create+profile API (T3), contacts CRUD+pagination+authz (T4), templates API (T5), audit/docs/contract deliverables (T6).
- Event-listener async timing is the riskiest seam — T3 documents the fallback (synchronous AFTER_COMMIT listener) explicitly.
- AuthenticatedUser move is a cross-cutting refactor confined to T3; ModularityTests guard it.
