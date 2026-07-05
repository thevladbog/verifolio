# Implementation History

Chronological record of delivered iterations. Agents: read this before starting work to
inherit context; append an entry when an iteration ships.

## 2026-07 — Backend bootstrap + identity slice

### What exists

- **Stack**: Kotlin 2.1.21 / Spring Boot 3.5.3 / Spring Modulith 1.4.1 / Gradle 9.6.1
  wrapper / Java 21 toolchain (local JVM may be JDK 25; Gradle accepts it).
- **Database**: Flyway migration `V1__identity_and_audit.sql` establishes four tables:
  `user_account`, `magic_link_token`, `user_session`, `audit_event`.
- **jOOQ**: codegen runs at build time via a throwaway Testcontainers postgres
  (`./gradlew generateJooq`); output lands in `build/generated-jooq` and is never
  committed. Runtime jOOQ version pinned to codegen version via `extra["jooq.version"]`.
- **Modulith**: 16 module packages (`identity`, `profiles`, `organizations`, `contacts`,
  `requests`, `templates`, `documents`, `files`, `verification`, `signatures`,
  `workflows`, `notifications`, `audit`, `admin`, `privacy`, `platform`) with marker
  objects; `ModularityTests` verifies boundaries; generated jOOQ package excluded from
  the module model.
- **Identity module** (`POST /api/v1/auth/magic-links`, `POST /api/v1/auth/sessions`,
  `GET/DELETE /api/v1/auth/sessions/current`): anti-enumeration 202 on magic-link
  requests; HMAC-hashed tokens (15-min TTL, single-use, reissue invalidates previous);
  find-or-create account on token consumption; `verifolio_session` cookie
  (HttpOnly/Secure/SameSite=Strict, 30d TTL); CSRF via XSRF-TOKEN cookie +
  X-XSRF-TOKEN header (bypassed only on the two public POST auth entry points); per-email
  5/15 min + per-IP 100/15 min in-process sliding-window rate limits (429 RATE_LIMITED).
- **Audit module**: append-only `AuditService` with `REQUIRES_NEW` propagation so events
  survive caller rollbacks. Events: `MAGIC_LINK_REQUESTED`, `MAGIC_LINK_CONSUMED`,
  `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `SESSION_CREATED`, `SESSION_REVOKED`, `LOGOUT`.
  IP and user-agent values stored as keyed-HMAC hashes.
- **Notifications**: `MailPort` interface + `SmtpMailAdapter` (Mailpit locally);
  `RecordingMailPort` for tests.
- **Platform module**: `VerifolioProperties` at package root (public API);
  `ApiException` at package root; `platform.web` subpackage exposed via
  `@NamedInterface("web")` containing `ApiError`, `GlobalExceptionHandler`,
  `OpenApiConfig`, `ApiDocsUiController`.
- **OpenAPI**: contract served at `/v3/api-docs(.yaml)`; Scalar UI at `/docs`;
  committed snapshot at `apps/backend/api/openapi.yaml`; guarded by
  `OpenApiContractTest`. Refresh with
  `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`.
- **Tests**: 29 tests (unit + Testcontainers integration). Integration tests extend
  `testsupport.IntegrationTest` (shared postgres container) and import
  `RecordingMailConfig`.
- **CI**: `.github/workflows/backend.yml` (ubuntu-latest, JDK 21, `./gradlew build`,
  path-filtered) + `dependabot.yml` (gradle + github-actions, weekly).
- **Infrastructure**: `docker-compose.yml` at repo root with postgres:17-alpine, MinIO,
  Mailpit (SMTP 1025 / UI 8025), Temporal auto-setup (gRPC 7233) + Temporal UI (8088).

### Conventions established

| Convention | Detail |
|---|---|
| Module public API | Exposed at package root; internal logic in subpackages |
| Secret hashing | `TokenHasher` HMAC for all tokens and sensitive values |
| Audit discipline | Every sensitive action calls `AuditService.record` with `REQUIRES_NEW` |
| API errors | `ApiError{code, message, details}` thrown via `ApiException`, rendered by `GlobalExceptionHandler` |
| Integration tests | Extend `testsupport.IntegrationTest`; import `RecordingMailConfig` per test class |
| jOOQ sources | Never committed; always regenerated from migrations via `generateJooq` |
| OpenAPI contract | Snapshot committed to `api/openapi.yaml`; drift detected by `OpenApiContractTest` |

### Deferred items

- **Temporal integration** — docker-compose includes Temporal but the backend has no
  workflows wired (ADR-0005 deferred).
- **AuthIdentity / OAuth** — only magic-link auth exists; no OAuth providers.
- **Recommender invitation tokens** — token infrastructure exists but the recommender
  flow module is not implemented.
- **Admin authentication** — admin module package exists as a marker only.
- **Step-up re-confirmation** — no step-up auth for sensitive operations.
- **Distributed rate limiting** — current limits are in-process only; Redis-backed
  solution needed for multi-instance deployments.
- **Dedicated PII pepper** — IP/UA hashes share the token pepper; a dedicated pepper
  config key is the tracked fix.
- **Event-driven audit dispatch** — currently `REQUIRES_NEW` transaction (Hikari pool
  sized to 20); migration to `AFTER_COMMIT` event dispatch is the long-term fix.

## 2026-07 — Profiles, contacts, templates (iteration 2)

### What shipped

- **Schema**: Flyway V2 migration adds `person_profile`, `organization` (minimal),
  `recommender_contact`, and `template` tables. Flyway V3 seeds six English-locale
  templates (EMPLOYMENT_REFERENCE, IMMIGRATION_REFERENCE, VISA_SUPPORT_LETTER,
  ACADEMIC_RECOMMENDATION, CLIENT_TESTIMONIAL, CHARACTER_REFERENCE).
- **Profiles module** (`GET /api/v1/profile`, `PUT /api/v1/profile`): profile is
  auto-created synchronously via a `UserAccountCreated` application event published by
  the identity module (AFTER_COMMIT listener). Locale allowlist: `en`, `ru`. Audit
  events: `PROFILE_CREATED`, `PROFILE_UPDATED`.
- **Contacts module** (owner-scoped CRUD under `/api/v1/contacts`): keyset-cursor
  pagination (page size 50); `RelationshipType` enum
  MANAGER/COLLEAGUE/DIRECT_REPORT/CLIENT/PROFESSOR/MENTOR/PERSONAL/OTHER;
  `org_id` is not exposed in the API. Audit events: `CONTACT_CREATED`,
  `CONTACT_UPDATED`, `CONTACT_DELETED` (metadata contains `relationshipType` only —
  name and email are never included).
- **Templates module** (read-only, `GET /api/v1/templates?locale=`, `GET
  /api/v1/templates/{id}`): JSON schemas returned as objects. Template reads are not
  audited — templates contain no personal data and reads cross no authorization boundary.
- **Identity public API**: `AuthenticatedUser` principal type and `UserAccountCreated`
  event moved/created at `com.verifolio.identity` package root as shared module API.
- **OpenAPI snapshot**: refreshed to include all new endpoints.
- **Tests**: 43 tests green (unit + Testcontainers integration).

### Conventions established

| Convention | Detail |
|---|---|
| Cross-module communication | Application events published from the identity public API; other modules listen synchronously with `@TransactionalEventListener(phase = AFTER_COMMIT)` |
| Persistent event registry | Not yet implemented — tracked as a follow-up |
| Keyset-cursor pagination | Cursor = Base64(`ISO-createdAt\|id`); pageSize+1 lookahead to determine `hasNext` |
| Seed data | Ships as Flyway data migrations (e.g., V3) |
| Principal type | `com.verifolio.identity.AuthenticatedUser` is shared module API; inject via `@AuthenticationPrincipal` |

### Deferred items

- **Organizations API** — `organization` table is minimal (name + domains); the full
  organizations module is scheduled for v1.1.
- **Contact communication preferences** — not yet modelled.
- **CUSTOM template authoring** — templates are read-only in MVP; custom authoring is
  post-MVP.
- **RU locales** — locale allowlist accepts `ru` but no RU-locale templates are seeded yet.
- **Persistent event publication registry** — profile auto-creation relies on the
  synchronous AFTER_COMMIT listener; a persistent outbox/registry is the tracked
  long-term fix.

## 2026-07 — Reference requests, requester side (iteration 3)

### What shipped

- **Schema**: Flyway V4 migration adds `reference_request` (11-status check constraint;
  `recommender_name`/`recommender_email` snapshot the contact at creation so the
  attestation covers exactly that recipient — contact edits never redirect an attested
  invitation, and referenced contacts cannot be deleted: 409 `CONTACT_IN_USE`),
  `consent_record` (subject-attribution check: exactly one of `user_id` /
  `recommender_contact_id`, matched to `subject_type`; nullable `reference_request_id`
  FK links request-scoped consents), and `invitation_token` (unique `token_hash`).
- **Requests module** (`/api/v1/reference-requests`): create (blocking
  `verbalConsentAttested` checkbox → `REQUESTER_VERBAL_CONSENT_ATTESTATION` consent
  record written transactionally; 400 `CONSENT_REQUIRED` otherwise), `POST /{id}/send`
  (CREATED→SENT; mints invitation token; sends invitation email with tokenized link +
  decline/report-abuse frontend links; global per-recommender-email sliding-window rate
  limit → 429), `POST /{id}/cancel` (any non-terminal → CANCELLED; revokes outstanding
  invitation tokens), owner-scoped get/list (keyset cursor, optional `status` filter).
  State machine encoded in `requests.domain.ReferenceRequestStatus.canTransitionTo`.
- **Identity public API**: `InvitationTokenService` (mint returns raw token, stores HMAC
  hash via `TokenHasher`; `revokeForRequest` audits `INVITATION_TOKEN_REVOKED` per token).
- **New cross-module read APIs**: `contacts.ContactLookup.findOwned` (ContactSnapshot),
  `templates.TemplateLookup.exists` — package-root public interfaces.
- **Platform**: `SlidingWindowRateLimiter` promoted from `identity.infrastructure` to
  `platform` (shared technical infra); `VerifolioProperties` gains `consents.requesterAttestation`
  (versioned consent text id, stored as `textId:version` in `policy_text_version`) and
  `requests.{expiry(21d),sendLimitPerRecommender(5),sendLimitWindow(1d)}`.
- **Audit**: `REFERENCE_REQUEST_CREATED`, `CONSENT_GRANTED`, `REFERENCE_REQUEST_SENT`,
  `REFERENCE_REQUEST_CANCELLED`, `INVITATION_TOKEN_REVOKED`. Metadata carries IDs/status/
  consent metadata only — no names or emails.
- **Tests**: 77 tests green; new unit state-machine + limiter tests and 20 integration
  tests (consent gating, token hashing, rate limiting with refund-on-mail-failure,
  contact-snapshot immutability, owner isolation, DB constraints).
- **Docs/spec**: design spec `docs/superpowers/specs/2026-07-04-reference-requests-design.md`;
  plan `docs/superpowers/plans/2026-07-04-reference-requests.md`; OpenAPI snapshot refreshed.

### Deferred items

- **Recommender flow** — invitation open/confirm-email/consent-gate/decline endpoints
  (`/api/v1/invitations/{token}/...`), recommender sessions, responses. Decline and
  report-abuse links in the invitation email point to frontend routes whose backend
  ships with that iteration.
- **EXPIRED auto-transition** — `expires_at` stored and enforced on send; the EXPIRED
  status transition + reminders arrive with the "minimal workflows" (Temporal) item.
- **Requester attestation consent texts per region** — config carries `local` placeholder
  text id/version; real per-region texts land with region policy configuration.
- **Rate limiter remains in-process** — same limitation as auth rate limits.
- **Invitation email sent inside the transaction** — matches the MagicLinkService
  pattern; a failed send rolls everything back and refunds the limiter slot. The
  residual window (commit failure after a successful SMTP send) leaves a dead link
  only. Outbox/AFTER_COMMIT dispatch is the long-term fix, together with the
  event-driven audit dispatch item from iteration 1.

## 2026-07 — Recommender flow (iteration 4)

### What shipped

- **Schema**: Flyway V5 adds `recommender_session` (mirrors `user_session`),
  `email_confirmation_code` (HMAC-hashed 6-digit codes, TTL 10 min, max 5 attempts,
  attempt counter persisted via REQUIRES_NEW so it survives the CODE_INVALID rollback),
  and `reference_response` (`approved_letter_text` spec extension; partial unique index —
  one `submitted_at IS NULL` draft per request).
- **Auth model** (per AUTHENTICATION.md): invitation token is a credential only until
  email confirmation (consumed single-use there); the flow then runs under the
  `verifolio_recommender_session` cookie (TTL 1h, config
  `verifolio.auth.recommender-session-ttl`). New identity public API: `InvitationAccess`
  (peek/identify/issueEmailConfirmation/confirmEmail), `RecommenderSessions`
  (resolve/revokeForRequest), `RecommenderActor` principal +
  `RecommenderSessionAuthFilter`. `/api/v1/invitations/**` is public + CSRF-exempt;
  `/api/v1/recommender/**` authenticated with CSRF.
- **Endpoints** (requests module): token-scoped `GET /api/v1/invitations/{token}` (open;
  SENT→OPENED once), `POST .../email-confirmations` (202, rate-limited 3/15min),
  `POST .../confirm-email` (mints session cookie), one-click `POST .../decline` and
  `.../report-abuse` (work post-consumption while the request is non-terminal);
  session-scoped `GET /api/v1/recommender/request`, `POST /consent` (accept →
  RECOMMENDER_PROCESSING_CONSENT [+ CROSS_BORDER_TRANSFER_CONSENT] + IN_PROGRESS;
  decline → DECLINED consent record + DECLINED + session revoked),
  `PUT /response-draft`, `POST /responses` (confirmations required →
  IN_PROGRESS→SUBMITTED→NEEDS_REVIEW, session revoked).
- **API_GUIDELINES.md updated**: the former token-scoped consent/response sketch was
  replaced with the session-scoped shape (AUTHENTICATION.md single-use rule wins).
- **New error codes**: `CODE_INVALID` (400), `CONFIRMATION_REQUIRED` (400).
- **Audit**: REFERENCE_REQUEST_OPENED, RECOMMENDER_EMAIL_CONFIRMED,
  INVITATION_TOKEN_CONSUMED, CONSENT_GRANTED/DECLINED, REQUEST_DECLINED (metadata
  `reason`: declined | abuse_report | consent_declined), REFERENCE_RESPONSE_STARTED,
  REFERENCE_RESPONSE_SUBMITTED, RECIPIENT/RELATIONSHIP_CONFIRMED_BY_RECOMMENDER —
  actor RECOMMENDER, metadata IDs only.
- **Lookups extended**: `ProfileService.displayName`, `TemplateLookup.snapshot`
  (name + question schema).

### Deferred items

- **PII erasure on decline** — status + audit recorded now; physical erasure ships with
  the privacy module.
- **Cross-border consent necessity is client-decided** — the recommender's jurisdiction
  is not server-detectable; the backend records explicit grants. `local` cell requires
  only processing consent.
- **Recipient review / document generation** — next iteration (Documents): accept /
  correction-request in NEEDS_REVIEW, PDF, hashing, version lock.
- **Draft expiry & reminders** — with the Temporal "minimal workflows" item.
- **AI letter drafting, scan/signature uploads** — need provider/files modules.

## 2026-07 — Recipient review, documents, files slice, core signals (iteration 5)

### What shipped

- **Schema**: Flyway V6 adds `file_object` (status/purpose CHECKs), `document`
  (unique partial index on `request_id`), `document_version` (UNIQUE
  document_id+version_number; content columns nullable for future tombstoning; inserted
  already LOCKED — no update path to locked versions exists anywhere in code), and
  `verification_signal`.
- **files module** (minimal slice): `S3StorageAdapter` (AWS SDK v2, path-style MinIO,
  presigner) is the only S3-touching class; public `FileStore.storeGeneratedPdf`
  (opaque region-scoped keys, SHA-256 of bytes, inserted READY — backend-generated bytes
  skip the upload validation pipeline) and `FileStore.presignedDownloadUrl` (5m TTL).
  Testcontainers MinIO joined the shared `IntegrationTest` containers.
- **documents module**: `DocumentPublisher.publishLockedVersion` — find-or-create by
  request, canonical-JSON (sorted keys) content hash, escaped-HTML render, openhtmltopdf
  PDF, version inserted LOCKED, `current_version_id` updated. Recipient API:
  `GET /api/v1/documents`, `GET /{id}`, `GET /{id}/versions/{n}/download-url`
  (presigned GET + `FILE_DOWNLOAD_GRANTED` audit).
- **verification module**: `VerificationSignals.createVerified` (single owner of signal
  rows; audits `VERIFICATION_SIGNAL_CREATED`). Read model deferred.
- **requests orchestration**: `POST /{id}/accept` (NEEDS_REVIEW→COMPLETED; publishes the
  locked version; creates RECIPIENT_CONFIRMED, RECOMMENDER_RELATIONSHIP_CONFIRMED,
  EMAIL_CONFIRMED, CORPORATE_DOMAIN_CONFIRMED (suffix-safe free-email deny-list from
  config), VERSION_LOCKED, DOCUMENT_HASH_LOCKED; audits REFERENCE_RESPONSE_ACCEPTED)
  and `POST /{id}/request-correction` (NEEDS_REVIEW→CORRECTION_REQUESTED; fresh
  invitation token + email with optional non-persisted message). Recommender return:
  first draft save flips CORRECTION_REQUESTED→IN_PROGRESS.
- **Versioning semantics**: corrections happen before acceptance (COMPLETED is terminal),
  so an MVP request yields exactly one accepted locked version; multi-version support in
  `documents` exists for the future DSR CORRECTION flow.
- **Dependencies**: AWS SDK v2 (BOM 2.31.78), openhtmltopdf-pdfbox 1.0.10 (LGPL,
  server-side), testcontainers-minio.

### Deferred items

- **User uploads** — presigned PUT flow, async validation pipeline (Temporal), generic
  `/api/v1/files/{id}/download-url`.
- **Tombstoning** — privacy module; content columns already nullable.
- **NAME_MATCH signal** — needs a structured recipient-name field in template answers.
- **Signals read API / trust summary** — with the public verification page.
- **PDF generation stays synchronous in the accept transaction** — Temporal
  orchestration with the "minimal workflows" item.
