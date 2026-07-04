# Verifolio Backend

Kotlin 2.1.21 / Spring Boot 3.5.3 / Spring Modulith 1.4.1, Gradle 9.6.1 wrapper, Java 21 toolchain.

## Prerequisites

- **JDK 21+** — Gradle's build script (jOOQ codegen) requires a Java 21+ JVM.
  On machines where `java` resolves to JDK 25, prefix commands with
  `JAVA_HOME=/path/to/jdk-25.jdk/Contents/Home` (Gradle accepts JDK 25 for the JVM
  while the toolchain targets Java 21 for compilation).
- **Docker + docker compose** — required for the Testcontainers-based jOOQ codegen and
  integration tests, and for the local services stack.

Start local services from the **repo root**:

```bash
docker compose up -d
```

This starts postgres:17-alpine, MinIO, Mailpit (SMTP 1025 / UI 8025), Temporal
auto-setup (gRPC 7233), and Temporal UI (8088).

## Commands

Run all commands from `apps/backend/` (or prefix with `-p apps/backend`).

| Goal | Command |
|---|---|
| Start dev server | `./gradlew bootRun` |
| Run all tests | `./gradlew test` |
| Regenerate jOOQ sources | `./gradlew generateJooq` |
| Full build (compile + test) | `./gradlew build` |
| Refresh OpenAPI contract snapshot | `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"` |

## API Documentation

- **OpenAPI 3.1 JSON** — `http://localhost:8080/v3/api-docs`
- **OpenAPI 3.1 YAML** — `http://localhost:8080/v3/api-docs.yaml`
- **Scalar UI** — `http://localhost:8080/docs`

The committed contract snapshot lives at `apps/backend/api/openapi.yaml`. It is the
Orval input for the frontend codegen (wired up when `apps/web` is bootstrapped).
`OpenApiContractTest` fails the build if the running server drifts from the snapshot.
To refresh the snapshot after intentional contract changes, run the `UPDATE_OPENAPI`
command above.

## Adding a Database Migration

1. Add `apps/backend/src/main/resources/db/migration/V<n>__description.sql`.
   **Never edit or remove an already-applied migration.**
2. Regenerate jOOQ: `./gradlew generateJooq` (spins up a throwaway Testcontainers
   postgres, runs Flyway, then generates sources into `build/generated-jooq`).
   Generated sources are **never committed or manually edited**.
3. Update `docs/DATA_MODEL.md` to reflect schema changes.

## Module Layout

The application is split into 16 Spring Modulith packages under
`com.verifolio`:

```text
identity, profiles, organizations, contacts, requests, templates, documents,
files, verification, signatures, workflows, notifications, audit, admin,
privacy, platform
```

Each module exposes its public API at the **package root** via a marker/aggregate
object. Internal implementation lives in subpackages and is not accessible to other
modules. Module boundaries are verified at test time by `ModularityTests`.

The `platform.web` subpackage is exposed as a named interface
(`@NamedInterface("web")`) so other modules can depend on `ApiError`,
`GlobalExceptionHandler`, `OpenApiConfig`, and `ApiDocsUiController`.

The generated jOOQ package (`com.verifolio.jooq`) is excluded from the
Modulith module model.

## Auth Flow

### Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/auth/magic-links` | Request magic link; always 202 (anti-enumeration) |
| POST | `/api/v1/auth/sessions` | Consume token; sets `verifolio_session` cookie |
| GET | `/api/v1/auth/sessions/current` | Returns current session info |
| DELETE | `/api/v1/auth/sessions/current` | Logout (idempotent) |

### Cookies

`verifolio_session` is set as HttpOnly / Secure / SameSite=Strict with a 30-day TTL.

### CSRF for SPA Clients

CSRF protection is active on all state-mutating requests **except** the two POST auth
entry points (magic-link request and session creation, which are public and
unauthenticated). For all other mutating requests from a browser SPA:

1. Read the `XSRF-TOKEN` cookie (set by the server on first response).
2. Send the value as the `X-XSRF-TOKEN` request header.

### Rate Limits

- Per email: 5 requests / 15 min
- Per IP: 100 requests / 15 min

Exceeding either limit returns `429 RATE_LIMITED`. Limits are in-process
(sliding-window); distributed rate limiting is a tracked follow-up.

## Local Mail

Mailpit captures all outbound SMTP from the backend.
Open `http://localhost:8025` to inspect magic-link emails.

## Known Follow-ups

- **Dedicated PII pepper** — IP/UA hashes currently share the token pepper; a separate
  pepper config key is the tracked fix.
- **Event-driven audit dispatch** — audit events are currently written in
  `REQUIRES_NEW` transactions (sized Hikari pool to 20 to support the second
  connection); migrating to `AFTER_COMMIT` event dispatch is the long-term fix.
- **Distributed rate limiting** — current limits are in-process only; a Redis-backed
  solution is required for multi-instance deployments.
