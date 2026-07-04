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
