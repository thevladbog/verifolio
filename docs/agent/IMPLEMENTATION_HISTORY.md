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

## 2026-07 — Share links & public verification page (iteration 6)

### What shipped

- **Schema**: Flyway V7 adds `share_link` (version-pinned `document_version_id`, unique
  HMAC `token_hash`, `expires_at` nullable = no expiry, `revoked_at`).
- **Platform**: `TokenHasher`/`TokenGenerator` promoted from identity.domain to platform
  (documents needs hashing; pure technical utils like the rate limiter).
- **documents**: share-link lifecycle — `POST /api/v1/documents/{id}/share-links`
  (pins the current version; raw token returned exactly once in
  `${frontendBaseUrl}/verify/{raw}`), owner-scoped list (never returns tokens),
  `POST /api/v1/share-links/{id}/revoke` (immediate; double revoke 409). Public API
  `ShareLinkAccess.resolve/presignPinnedPdf` (null/404 for unknown, revoked, expired,
  tombstoned). Audits SHARE_LINK_CREATED/REVOKED.
- **publicpages (new module)**: read-only composition layer on top of documents/requests/
  profiles/verification — hosting the page inside `verification` created dependency
  cycles (domain modules write signals INTO verification while the page reads FROM them);
  ModularityTests caught it and MODULES.md documents the new module. Owns
  `GET /api/v1/verification-pages/{token}`
  (header, recipient, recommender labeled stated-by-recommender, badge list from the
  catalog texts, trust summary counts per category — VERIFIED only, never a single
  number, version info incl. supersededByNewerVersion, timeline from entity timestamps,
  disclaimer + privacy notice) and `GET .../download-url` (presigned PDF). permitAll,
  per-IP limiter 300/15min, 404 for any invalid token (no state oracle). View audit
  sampled via `verifolio.public-page.view-audit-sample-rate` (local 1.0); downloads
  always fully audited (PUBLIC_VERIFICATION_PAGE_DOWNLOAD).
  `VerificationSignals` gained `listVerified`/`markRevoked` (audits
  VERIFICATION_SIGNAL_UPDATED); `TrustSummary`/`BadgeCatalog` live at the verification
  package root as public display API. PUBLIC_VERIFICATION_ENABLED signal per link (entity
  SHARE_LINK): VERIFIED at creation, REVOKED at revocation.
- **Test infrastructure**: the per-IP magic-link limit became configurable
  (`verifolio.auth.magic-link-ip-limit`, default 100) — the grown suite shares one
  context and one client IP and had started tripping the hardcoded limit.
- **requests**: `RequestPublicView` read model (recommender snapshot fields, purpose,
  timestamps, latestResponseId) — no emails, no letter content.

### Deferred items

- **Expired-link signal sweep** — access stops at expiry (resolve check), but the
  PUBLIC_VERIFICATION_ENABLED row flips only on revocation; background sweep with the
  workflows item.
- **Verification certificate PDF, scan/signature download sections, NAME_MATCH,
  retraction/tombstone page states** — with their features.
- **View sampling is in-process randomness** — per-cell aggregation post-MVP.

## 2026-07 — Recommender uploads (iteration 7)

### What shipped

- **Schema**: Flyway V8 adds `response_upload` (kind SCAN/SIGNED_PDF/DETACHED_SIGNATURE/
  ATTACHMENT, self-FK `target_upload_id` for signature→scan, `shared_publicly`,
  `consent_record_id`) and `document_attachment` (per DATA_MODEL).
- **files**: `FileUploads` public API — requestUpload (PENDING FileObject, constrained
  presigned PUT: signed content-type + content-length, TTL 10m, opaque keys, per-purpose
  MIME allowlists, 15 MB cap) / confirmUpload (synchronous validation inside the call:
  head-size match, `MimeSniffer` magic bytes, SHA-256; REJECTED deletes the object) /
  deleteUpload (physical). Audits FILE_UPLOAD_REQUESTED/FILE_UPLOADED/FILE_VALIDATED/
  FILE_DELETED.
- **Recommender API**: `POST/GET/DELETE /api/v1/recommender/uploads` +
  `POST /{id}/confirm` — same response-cycle gate as drafts; cap 10 per request;
  DETACHED_SIGNATURE requires a READY SCAN/SIGNED_PDF target (a signature covers a
  specific uploaded file, never the generated PDF); deleting a signature target is
  blocked while the signature exists. `sharedPublicly` on confirm writes a per-upload
  `RECOMMENDER_PUBLIC_SHARING_CONSENT` record (versioned text
  `verifolio.consents.public-sharing`) linked via `consent_record_id`.
- **Acceptance**: READY uploads become `document_attachment` rows on the locked version
  (`DocumentPublisher.attachFiles`); signals `SCAN_ATTACHED` (once) and
  `SIGNATURE_ATTACHED` (per signature, evidence carries signatureFileId + targetFileId +
  format "CMS/CAdES (detached)").
- **Public page**: `downloads[]` section (generated PDF always; attachments downloadable
  only with a GRANTED non-withdrawn consent; unconsented ones listed without filename);
  `GET /api/v1/verification-pages/{token}/attachments/{attachmentId}/download-url`
  (operationId publicAttachmentDownloadUrl) with full download audit.
- **Tests**: presigned PUT exercised for real (HttpClient PUT to MinIO from tests);
  content-mismatch rejection; signature-target rules; consent gating on public downloads.

### Deferred items

- **Antivirus / async validation pipeline** — Temporal item; VALIDATING runs inside confirm.
- **PENDING-TTL cleanup job** — workflows item (abandoned PENDING rows/objects remain until then).
- **Signature verification (Signature table, providers)** — ADR-0007, v1.1; only
  SIGNATURE_ATTACHED is asserted.
- **Consent withdrawal effects on published attachments** — retraction/privacy flows.

## 2026-07 — Minimal workflows (iteration 8) — MVP backend feature-complete

### What shipped

- **workflows module** (first real code): `RecurringTask` public interface + DB-backed
  scheduler runner — the ADR-0005 MVP fallback, engaged and recorded in the ADR's
  implementation note. No engine APIs leak into domain modules; the Temporal migration
  replaces the runner behind the same interface. Disabled in integration tests
  (`verifolio.workflows.enabled=false`) — tests call `run()` directly, no sleeps.
- **Schema**: Flyway V9 adds `reference_request.sent_at` (reminder anchor, set by send),
  `reminders_sent`, `reminders_stopped_at`.
- **ReferenceRequestLifecycleTask** (requests): reminders at configurable offsets
  (`verifolio.workflows.reminder-offsets`, default 3d/7d/14d from sent_at; the last one
  carries the expiration-warning copy); each reminder re-mints the invitation token
  (raw tokens are never stored) after revoking outstanding ones; per-row transactions —
  a mail failure leaves `reminders_sent` unchanged and retries next tick. Auto-EXPIRED
  for past-due active requests with token+session revocation. New audit events
  (catalog updated): `REFERENCE_REQUEST_REMINDER_SENT` (SYSTEM), `REMINDERS_STOPPED`
  (RECOMMENDER).
- **One-click stop-reminders**: `POST /api/v1/invitations/{token}/stop-reminders`
  (works post-consumption, idempotent); the stop link now appears in ALL recommender
  emails (invitation, correction, reminders) — closing the RECOMMENDER_EXPERIENCE.md
  "every email" gap.
- **PendingUploadCleanupTask** (files): PENDING uploads older than
  `verifolio.workflows.pending-upload-ttl` (24h) → object deleted, status DELETED,
  FILE_DELETED audit.
- **ExpiredShareLinkSignalTask** (documents): expired unrevoked links → their
  PUBLIC_VERIFICATION_ENABLED signal flips to EXPIRED via new
  `VerificationSignals.markExpired` (closing the iteration-6 deferred sweep).

### Deferred items

- **Temporal migration** — the runner swap behind `RecurringTask`; docker-compose
  Temporal services remain in place.
- **Distributed scheduler locking** — single-instance assumption, consistent with the
  in-process rate limiters; required before multi-instance cells.
- **Draft grace-extension near expiry** — the day-14 warning covers notification; the
  extension logic itself is not implemented.
- **Staging-key S3 orphans** — still need an S3 lifecycle rule (DB-less objects are
  invisible to the cleanup task).
