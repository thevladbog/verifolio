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
- **ReferenceRequestLifecycleTask** (requests): expiration runs BEFORE reminders (a
  downtime-delayed tick must not email a link that dies moments later); reminders at
  configurable offsets (`verifolio.workflows.reminder-offsets`, default 3d/7d/14d from
  sent_at; the last one carries the expiration-warning copy) under the global
  per-recommender-email send limiter (Reminder Policy); each reminder mints a fresh
  token, emails it, and only then revokes the older ones (`revokeForRequest(createdBefore)`)
  — revocation audits run REQUIRES_NEW and must not survive a mail-failure rollback;
  per-row transactions — a mail failure leaves `reminders_sent` unchanged and retries
  next tick. Auto-EXPIRED for past-due active requests with token+session revocation
  and unsubmitted-draft erasure (drafts expire with the request, AUTHENTICATION.md).
  New audit events (catalog updated): `REFERENCE_REQUEST_REMINDER_SENT` (SYSTEM),
  `REMINDERS_STOPPED` (RECOMMENDER).
- **One-click stop-reminders**: `POST /api/v1/invitations/{token}/stop-reminders`
  (works post-consumption, idempotent); the stop link now appears in ALL recommender
  emails (invitation, correction, reminders) — closing the RECOMMENDER_EXPERIENCE.md
  "every email" gap.
- **PendingUploadCleanupTask** (files): PENDING uploads older than
  `verifolio.workflows.pending-upload-ttl` (24h) → object deleted, status DELETED,
  FILE_DELETED audit. Rows flip to DELETED only after the storage delete succeeds
  (failed deletes stay PENDING and retry); bounded batches of 100, S3 calls outside
  any DB transaction.
- **ExpiredShareLinkSignalTask** (documents): expired unrevoked links → their
  PUBLIC_VERIFICATION_ENABLED signal flips to EXPIRED via new
  `VerificationSignals.markExpired`, plus the mandatory `SHARE_LINK_EXPIRED` audit
  (exactly once per link — gated on the flip) (closing the iteration-6 deferred sweep).

### Deferred items

- **Temporal migration** — the runner swap behind `RecurringTask`; docker-compose
  Temporal services remain in place.
- **Distributed scheduler locking** — single-instance assumption, consistent with the
  in-process rate limiters; required before multi-instance cells.
- **Draft grace-extension near expiry** — the day-14 warning covers notification; the
  extension logic itself is not implemented.
- **Staging-key S3 orphans** — still need an S3 lifecycle rule (DB-less objects are
  invisible to the cleanup task).

## 2026-07 — Frontend MVP (iteration 9)

### What shipped

- **`apps/frontend`**: Next.js 16 (App Router) + React 19 + TS strict + Tailwind v4;
  Node 22; the mandated `.npmrc` (`registry=https://registry.npmjs.org/`). Same-origin
  `/api/*` rewrite to `BACKEND_INTERNAL_URL` (SameSite=Strict cookies never cross
  origins); `proxy.ts` (Next 16 rename of middleware) does a UX-only session-cookie
  check for app routes.
- **API layer**: `openapi-typescript` types generated from the committed snapshot
  (`npm run gen:api`; CI `check:api` fails on drift) + `openapi-fetch` client with an
  X-XSRF-TOKEN middleware; `errorMessage` maps backend ApiError codes (verified against
  ApiException call sites) to i18n; TanStack Query provider handles 401→/login and
  toasts.
- **Design**: the claude.ai design project («Дизайн портала Verifolio») is committed
  at `docs/design/Verifolio Design.dc.html`; UI font Manrope; top-navbar shell per
  canvas (DESIGN_SYSTEM.md's dark sidebar superseded); design tokens as Tailwind
  `@theme`. Screens beyond the MVP API (custom templates, decline reason, GDPR
  self-service, public folio, doc-check by ID, notifications, admin, HTML emails,
  themed dark mode) are explicitly out — see the spec's deferred list.
- **Screens**: landing + magic-link login + `/auth/callback`; dashboard (client-side
  composition — no aggregate endpoint); contacts CRUD; 4-step request builder with the
  blocking verbal-consent attestation; request detail (timeline, send/cancel,
  NEEDS_REVIEW accept → generating state → document link, correction dialog);
  documents + versions + presigned downloads fetched on click; share links with
  one-time raw URL and revoke; recommender flow (invitation open → email code →
  consent gate with zero inputs pre-accept → dynamic question form from the template
  schema → 2s-debounced autosave → uploads via constrained presigned PUT with
  per-file public-sharing consent and signature-target rule → gated submit);
  one-click decline/report-abuse/stop-reminders (explicit confirm click — mail
  scanners must not trigger POSTs); SSR `/verify/[token]` with counts-only trust
  summary and "stated by recommender" labels.
- **i18n**: next-intl, `en`/`ru` full dictionaries, cookie-based locale (no URL
  prefix), profile.preferredLocale syncs the UI locale.
- **Tests**: 52 unit tests (Vitest+RTL, API mocked at the module boundary); 2
  Playwright E2E green against the real stack — the full canonical USER_FLOWS
  sequence (login→…→share→revoke→invalid) and the one-click actions.
- **CI/packaging**: `.github/workflows/frontend.yml` (lint, check:api, unit, build;
  E2E job non-blocking `continue-on-error` until proven on CI); multi-stage
  Dockerfile (standalone, non-root); compose `frontend` service.

### Conventions established

| Convention | Detail |
|---|---|
| API access | Only via the generated `api` client (`lib/api/client.ts`); regenerate with `gen:api` in the same PR as any OpenAPI change |
| Presigned URLs | Fetched on user action, `window.open` immediately, never rendered into HTML or stored |
| Trust display | `BadgeStatus` component; per-trust-type tones; counts per category; never a numeric score |
| Strings | next-intl keys in `messages/{en,ru}.json`; consent copy keyed by backend `textId` |
| Unit tests | Mock `@/lib/api/client` module; `renderWithProviders` (`lib/test/render.tsx`) |
| E2E | Playwright against compose infra + `bootRun`; emails/codes pulled from the Mailpit API; production `next start` (dev-mode long flows are flaky) |

### Deferred items

- **Design-fidelity pass (plan Task 12)** — claude_design MCP requires interactive
  consent (`/design-login`); reconcile every screen against the committed canvas.
- **Response review API gap** — the requester cannot read the submitted letter before
  accepting (no endpoint exposes response content pre-accept); the review card
  explains this. Needs a backend read model for real Flow-4 review.
- **Per-cell consent texts** — the API returns only versioned text refs; the frontend
  ships placeholder-cell copy keyed by textId. Real texts land with region policy
  config.
- **CI E2E promotion** — flip the e2e job to blocking once green on GitHub runners.
- **Onboarding profile step (design 8a)** — callback goes straight to the dashboard.
- **Landing** — minimal hero per approved open question; full marketing page later.

## 2026-07 — Frontend API tails (iteration 10)

### What shipped

- **Schema**: Flyway V10 adds `reference_request.declined_reason` (nullable, CHECK on
  DONT_KNOW_REQUESTER / TOO_BUSY / NOT_COMFORTABLE / OTHER).
- **Decline reason** (requests): `POST /api/v1/invitations/{token}/decline` accepts an
  optional `{reasonCategory}` body; `ConsentDecisionRequest` gains the same optional
  field (applied on DECLINED only). The category lands in the same CAS UPDATE as the
  status flip, appears in `REQUEST_DECLINED` audit metadata (enum name only — never
  free text; free-text reasons were rejected as a PII/erasure liability), and is
  exposed to the owner as `declinedReason`. No-body decline unchanged.
- **Owner response review** (requests): `GET /api/v1/reference-requests/{id}/response`
  (`ResponseReviewController`/`Service`) — owner-scoped read of the latest submitted
  response: letter text, parsed answers, confirmations, READY upload metadata
  (kind/contentType/sizeBytes/sharedPublicly/targetUploadId — no URLs, no downloads;
  pre-accept owner downloads deferred). 404 for foreign request or no submitted
  response. Reads not audited (templates precedent: no new authz boundary).
- **Consent texts** (templates): `GET /api/v1/consent-texts/{consentType}?locale=`
  serves the ACTIVE versioned policy copy from classpath resources
  `consent-texts/{textId}/{version}/{locale}.md` (en+ru for the four `local-*` texts;
  copy moved from frontend i18n). Fallback any-locale→en; startup fails fast if an
  active text lacks `en`; permitAll, GET-only. `consent_record.policy_text_version`
  now provably matches served copy.
- **OpenAPI** snapshot refreshed; frontend consumes all three (review panel on real
  data, consent gate/attestation/sharing toggle render backend copy, decline page
  optional reason select).

### Deferred items

- **Dashboard aggregate endpoint** — frontend list-composition causes no pain yet.
- **Pre-accept upload downloads for the owner** — needs new files authorization.
- **Per-region consent text sets** (eu-*/ru-*) — resources+config mechanism is ready;
  real regional texts land with the RU cell / region policy work.

## 2026-07 — Privacy / DSR core (iteration 11)

### What shipped

- **Schema**: Flyway V11 adds `data_subject_request` (5 types, 5 statuses, subject =
  exactly one of user/recommender-contact, `due_at` = created + region SLA) and
  `dsr_verification_code` (HMAC 6-digit, TTL 10m, 5 attempts). Column additions:
  `reference_request.recommender_pii_erased_at`, `.declined_at` (erasure-grace anchor,
  stamped by the DECLINED transition), snapshot columns `recommender_name/email` +
  `invitation_token.recommender_email` made nullable (erasure nulls them);
  `document_version.retracted_at`.
- **privacy module** (first real code): `DataSubjectRequestService` (create/list/verify/
  execute) with a **hybrid execution model** — CONSENT_WITHDRAWAL auto-executes on
  email verification (GDPR Art. 7(3)); DELETION-of-recommender tombstones + erases;
  EXPORT / REGION_MIGRATION / CORRECTION / account-holder DELETION accepted but
  `execute()` throws NOT_IMPLEMENTED (manual until iteration 12+, no HTTP execution
  endpoint). Two channels: owner session (`POST/GET /api/v1/privacy/data-subject-requests`)
  and account-less recommender (`POST /api/v1/privacy/recommender-requests` always 202
  anti-enumeration + `/{id}/verify` with the emailed code). Per-email 3/15min +
  per-IP 100/15min limiters.
- **Recommender PII erasure** (`requests.RecommenderPiiErasure.eraseForRequest`): the
  normative erasure matrix — nulls request snapshot + stamps erased_at, deletes
  responses / recommender_session / email_confirmation_code rows, nulls invitation
  token email, physically deletes unattached uploads via `files.FileUploads.
  deleteUploadAsSystem`; leaves contact/consent/attachments/audit untouched. Idempotent.
  `RecommenderPiiErasureTask` (RecurringTask) erases DECLINED requests past the 24h
  grace. Audit `RECOMMENDER_PII_ERASED` (SYSTEM, counts).
- **Retraction / consent withdrawal** (Flow 10): `requests.ConsentWithdrawal.
  withdrawForRequest` (GRANTED→WITHDRAWN), `verification.VerificationSignals.
  revokeAllForEntity`, `documents.DocumentRetraction.markRetracted` (sets `retracted_at`,
  locked content untouched). Public page shows the retracted banner + REVOKED signal
  badges (new `listForDisplay` surfaces VERIFIED+REVOKED); generated PDF stays
  downloadable.
- **Tombstoning** (`documents.DocumentTombstone.tombstone`): S3-delete PDF + attachments
  via files module, then NULL content + status TOMBSTONED + tombstoned_at; sha256 /
  version_number / locked_at retained (asserted). Public page → neutral "content
  removed" state, download-url → 404.
- **New public APIs added**: `files.FileUploads.deleteUploadAsSystem`,
  `files.FileStore.deleteGeneratedAsSystem`, `verification.VerificationSignals.
  {revokeAllForEntity,listForDisplay}`, `documents.DocumentRetraction.versionIdsForRequest`,
  `requests.ConsentWithdrawal.{withdrawForRequest,findRequestsByRecommenderEmail}`.
- **Frontend**: public verify page retracted/tombstoned states; profile "Data & privacy"
  DSR submit + list; public `/data-requests` recommender email→code→type flow.
- **Audit/OpenAPI/docs**: catalog + OpenAPI snapshot refreshed; DATA_MODEL / AUDIT_EVENTS
  / API_GUIDELINES / SECURITY / PUBLIC_VERIFICATION_PAGE updated.

### Deferred items

- **DSR executors**: EXPORT, account-holder full DELETION, REGION_MIGRATION,
  CORRECTION-as-automation — `execute()` throws NOT_IMPLEMENTED; manual until then.
- **Admin review UI** for DSR lifecycle (IN_REVIEW/APPROVED/REJECTED transitions exist
  in the service but have no HTTP surface) — with the admin module.
- **SLA-breach reminder sweep** — due_at stored, no notifier yet.
- **Step-up re-confirmation** for DSR submission — still deferred (session + audit is
  the current bar; noted in SECURITY.md).
- **Audit-event retention/pseudonymization window** — not yet enforced.
- **Orphan-reconciliation sweep for erasure/tombstone S3 deletes** — the storage deletes
  now commit before the DB row mutations (tombstone + PII erasure), so a rollback can no
  longer orphan storage; but `deleteUploadAsSystem`/`deleteGeneratedAsSystem` still swallow
  a failing S3 delete while flipping the row to DELETED, so a truly-failed object delete
  can linger. Needs the same S3 lifecycle-rule sweep as the staging-key orphans above.

## 2026-07 — Organizations registry (iteration 12)

### What shipped

- **Schema**: Flyway V12 (`organization_domains`) adds the `organization.verification_status`
  CHECK (UNVERIFIED | VERIFIED | REVOKED), the authoritative `organization_domain` side
  table (org fk + `domain`), a unique `lower(domain)` index, and an org fk index. V13
  (`seed_organizations`) seeds a curator-replaceable starter set of VERIFIED organizations
  (real public primary domains, fixed UUIDs) — one `organization` row each plus its
  `organization_domain` rows, mirrored into the legacy `organization.domains` jsonb. jOOQ
  regenerated (`ORGANIZATION_DOMAIN` table).
- **organizations module** (first code): `OrganizationLookup.findVerifiedByDomain`
  (public API) — lowercase-normalized, suffix-aware, longest-domain-wins, VERIFIED-only —
  plus `OrganizationQueryService` + `OrganizationController` read API: `GET
  /api/v1/organizations?query=&cursor=` (name/domain prefix, keyset cursor page 50),
  `/{id}` (404), `/lookup?domain=` (→ `OrganizationView` or 404). Authenticated; reads not
  audited (templates precedent). Module depends only on platform + audit (ModularityTests
  green); requests → organizations, never the reverse.
- **CORPORATE_DOMAIN_CONFIRMED strengthened** (`requests.ReferenceRequestService`): at
  acceptance the corporate-domain signal calls `OrganizationLookup.findVerifiedByDomain`
  and, when a VERIFIED org owns the recommender's email domain, snapshots
  `organizationNameSource="verified-record"` + `organizationId` + `organizationName` into
  the signal evidence (gating-moment snapshot — immune to later registry changes);
  otherwise `organizationNameSource="recommender-stated"` (unchanged behaviour). No new
  signal type, no BadgeCatalog trust-semantics change.
- **Public page provenance** (`publicpages.PublicVerificationPageService` + `BadgeDto`):
  `SignalView` now carries the signal's `evidence` (read from the evidence_json already
  loaded). The CORPORATE_DOMAIN_CONFIRMED badge surfaces `organizationName` +
  `organizationSource="verified-record"` from that snapshot when present — no new lookup on
  the public path, no personal data (the company name is public). Recommender-stated and
  all non-corporate badges keep the existing framing (both fields null).
- **Audit/OpenAPI/docs**: OpenAPI snapshot refreshed (organizations endpoints + new
  `BadgeDto.organizationName`/`organizationSource`); DATA_MODEL / API_GUIDELINES /
  VERIFICATION_SIGNALS / MODULES updated. (Frontend rendering of the provenance +
  builder hint lands separately in iteration 12's frontend task.)

### Deferred items

- **Org management / verification via admin** — no write/management endpoints; the registry
  is seed-and-curate only until the admin module (UNVERIFIED→VERIFIED→REVOKED transitions
  have no HTTP surface).
- **Domain-ownership proof** (email/DNS challenge) — VERIFIED status is curator-asserted,
  not proven by the domain owner.
- **Contact ↔ organization auto-linking** — recommenders/contacts are not associated with
  organization rows; the match is purely by email domain at acceptance.
- **NAME_MATCH signal** — no cross-check of the recommender-stated company name against the
  verified org name.
- **RU / GLOBAL cell org seeds** — V13 seeds a single starter set; region-specific
  registries are not yet seeded.

## 2026-07 — Admin foundation + DSR queue (iteration 13)

### What shipped

- **Schema**: Flyway V14 adds `admin_account` (user_account FK, region, role
  SUPPORT_L1/L2/SUPERADMIN, status, `totp_secret_enc`, `mfa_enrolled_at`),
  `admin_magic_link_token`, `admin_session`, `admin_mfa_pending`.
- **Admin auth — magic-link + mandatory TOTP MFA** (fully isolated from user/recommender
  auth: separate tables, cookies `verifolio_admin_session`/`verifolio_admin_pending`, and
  a second `SecurityFilterChain @Order(1)` scoped to `/api/v1/admin/**`; the user chain is
  `@Order(2)`). A session is minted ONLY after both factors pass. Flow: `POST
  /admin/auth/magic-links` (always 202 anti-enum, rate-limited) → `/consume` (→ pending
  cookie + ENROLL|CHALLENGE state) → enroll (`GET /mfa/enrollment` secret+otpauth URI,
  `POST /mfa/enroll`) or challenge (`POST /mfa/verify`, attempt-capped atomic claim) →
  session; `GET /me`, `POST /logout`. TOTP via `com.eatthepath:java-otp` (RFC 6238,
  ±1 step skew); **secret AES-256-GCM encrypted at rest** (`AdminTotpCipher`, per-cell
  key `verifolio.admin.totp-secret-key`).
- **RBAC**: fixed code-defined role→permission map (`AdminRole.has`); `AdminAuthorization.
  require` → 403. L1 = DSR_VIEW; L2/SUPERADMIN add DSR_DECIDE + DSR_EXECUTE; ADMIN_MANAGE
  = SUPERADMIN only.
- **Bootstrap**: `verifolio.admin.bootstrap-emails` → idempotent SUPERADMIN creation on
  ApplicationReady (the only first-admin path this iteration), audited ADMIN_ACCOUNT_CREATED.
- **DSR review queue** (admin, region-scoped): privacy exposes `DataSubjectRequestAdminView`
  (listForRegion/get/counts + region-scoped approve/reject/execute — admin never touches
  DSR tables). `GET /admin/dashboard`, `GET /admin/data-subject-requests` (+`/{id}`) audit
  ADMIN_DSR_VIEWED (every admin read of subject data); approve/reject/execute record the
  ADMIN actor id. `execute()` on a not-yet-automated type → 409 EXECUTION_NOT_AUTOMATED.
- **Frontend**: `(admin)` route group — login → TOTP enroll/challenge, dashboard shell,
  DSR queue (role-gated actions).
- **Isolation invariants tested**: a user session cannot authenticate `/api/v1/admin/**`
  and an admin session cannot authenticate user endpoints; MFA-before-session; attempt
  caps; anti-enum; CSRF; region scoping.

### Deferred items

- **DSR automated executors** (EXPORT, account-holder DELETION, REGION_MIGRATION,
  CORRECTION) — still NOT_IMPLEMENTED; the queue lets admins decide + execute the
  automated types (recommender-DELETION; CONSENT_WITHDRAWAL auto at intake).
- **User management, audit-log viewer, identity-verification queue, org/catalog/consent
  write management** (design 5b/5c/5e/11a/11b/11c) — follow-up admin iterations.
- **Superadmin 2-step confirmation of critical actions** (step-up) — role gating only now.
- **Per-role field restrictions ("support without content access")** — with user mgmt.
- **ADMIN_LOGIN_FAILED emission**, admin-session TTL config (hardcoded 8h), RFC-strict
  otpauth label encoding — minor follow-ups flagged in review.
