# Backend Bootstrap + Identity Slice — Design

Date: 2026-07-04
Status: Approved by owner (pending written-spec review)

## Goal

Bootstrap the Verifolio backend inside this monorepo and prove the skeleton with the first
vertical slice: the `identity` module (magic-link authentication and sessions), implemented
strictly within the boundaries defined by the project documentation
(`AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/AUTHENTICATION.md`, `docs/SECURITY.md`,
`docs/API_GUIDELINES.md`, `docs/AUDIT_EVENTS.md`, ADR-0001…0008).

## Monorepo Layout

The repository stores all contours, each in its own folder:

```text
apps/backend/          # Kotlin Spring Boot backend (this iteration)
apps/frontend/         # Next.js frontend (later; consumes OpenAPI spec via Orval)
docker-compose.yml     # local infrastructure (per LOCAL_DEVELOPMENT.md)
docs/                  # project documentation (existing)
skills/                # agent skill packs (existing)
.github/               # CI workflows, PR template (existing + backend CI)
```

## Stack & Version Policy

- Java 21 (LTS), Kotlin (latest stable 2.x), Spring Boot (latest stable 3.x),
  Spring Modulith, Gradle with Kotlin DSL and version catalog (`gradle/libs.versions.toml`).
- PostgreSQL 17, Flyway (migrations, versioned only), jOOQ (codegen from migrations via
  Testcontainers during build; generated code is never edited by hand).
- **Version policy: always the latest stable and secure releases of all dependencies.**
  Versions are pinned in the version catalog; a dependency-update check is part of the
  Definition of Done for future iterations (Dependabot config included in this iteration).
- Local infra via Docker Compose: `postgres`, `minio`, `mailpit` (SMTP for magic-link mail),
  `temporal` + `temporal-ui` (UI on port 8088). Temporal is present in compose but backend
  integration is deferred — the identity slice does not need it (ADR-0005 unchanged).

## Backend Skeleton

- Single Gradle module; domain modules are packages `com.verifolio.<module>` verified by
  Spring Modulith boundary tests (per approved decision; matches `docs/ARCHITECTURE.md`).
- All 14 module packages created with `package-info.java`/`package-info.kt` describing the
  boundary; only `identity`, `audit` (minimal core), `notifications` (minimal mail port)
  contain real code in this iteration.
- Layered structure inside implemented modules: `api/`, `application/`, `domain/`,
  `infrastructure/`.
- `APP_REGION` in configuration; `local` is a development-only value
  (see `docs/REGION_POLICIES.md`).

## Identity Slice (per docs/AUTHENTICATION.md)

Endpoints (under `/api/v1`):

- `POST /auth/magic-links` — request a magic link (anti-enumeration: identical response
  whether or not the account exists; creates account on first login).
- `POST /auth/sessions` — consume a magic-link token, mint a server-side cookie session.
- `DELETE /auth/sessions/current` — logout.
- `GET /auth/sessions/current` — current user info.

Data (Flyway `V1__...` onward): `user_account`, `magic_link_token`, `session`, `audit_event`.

Security rules (all mandatory, from SECURITY.md/AUTHENTICATION.md):

- tokens stored hashed only (keyed HMAC with per-cell pepper from configuration/secret);
- magic links: 15-minute TTL, single-use, reissue invalidates prior tokens;
- rate limiting per email and per IP on magic-link requests (`RATE_LIMITED` / 429);
- session cookie: HttpOnly, Secure, SameSite; CSRF protection enabled;
- `ip_hash` / `user_agent_hash` are keyed HMAC, never raw values;
- no tokens, session IDs, or raw URLs in logs.

Audit events (append-only, via the minimal `audit` module): `MAGIC_LINK_REQUESTED`,
`MAGIC_LINK_CONSUMED`, `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `SESSION_CREATED`,
`SESSION_REVOKED`, `LOGOUT`.

Mail: `notifications` module exposes a mail port; local implementation sends via SMTP to
Mailpit. Regional provider selection is configuration-driven (design-ready, one provider now).

## API Contract, OpenAPI & Scalar

- The backend generates a **modern OpenAPI 3.1 specification** (springdoc) and serves it at
  `/v3/api-docs` (JSON/YAML).
- Interactive API reference via **Scalar** at `/docs` (Scalar API Reference consuming the
  generated spec). Enabled in dev; production exposure decided later.
- `openapi.yaml` is checked into the repo (`apps/backend/api/openapi.yaml`) as the exported
  contract; a contract-snapshot test fails the build if the served spec drifts from the
  committed file. This file is the input for frontend client generation with **Orval**
  (frontend iteration).
- Error format and codes per `docs/ERROR_HANDLING.md` / `docs/API_GUIDELINES.md`
  (`UNAUTHORIZED`, `VALIDATION_FAILED`, `RATE_LIMITED`, `CONFLICT`, `INTERNAL_ERROR`, …).

## Testing

TDD throughout. JUnit 5 + Testcontainers (PostgreSQL):

- Spring Modulith boundary verification test;
- integration tests for the full auth flow (request link → captured mail → consume →
  session → logout), including negative cases: expired token, reused token, invalidated
  prior token, rate limit, enumeration-safe responses;
- unit tests for domain rules (TTL, single-use, hashing);
- audit-event assertions for every sensitive action.

## CI

`.github/workflows/backend.yml`: Gradle build + all tests (with Testcontainers) on PR and
push to main, path-filtered to `apps/backend/**`. Existing `docs.yml` stays for docs.
Dependabot configuration for Gradle and GitHub Actions.

## Post-Development Deliverables (required by owner)

At the end of the iteration, based on what was actually built:

1. **Developer documentation** — how to run, build, test the backend; module layout;
   how to add a migration / regenerate jOOQ; how the OpenAPI/Scalar pipeline works
   (update `LOCAL_DEVELOPMENT.md` + `apps/backend/README.md`).
2. **User documentation** — only if user-facing behavior exists worth documenting
   (for this slice: none expected beyond login description; skip unless warranted).
3. **Agent data** — update agent-facing files with history and rules learned:
   what was built, decisions taken, conventions established (e.g. update `AGENTS.md`
   pointers, relevant `skills/*` packs, and a short implementation-history note under
   `docs/agent/`), so future agents inherit accurate context.

## Out of Scope (this iteration)

- AuthIdentity/OAuth providers, recommender invitation tokens, admin access, step-up auth;
- Temporal-backed workflows;
- all other domain modules beyond minimal `audit`/`notifications` cores;
- frontend (`apps/frontend`), Orval client generation (next iterations).

## Risks / Open Points

- jOOQ codegen via Testcontainers requires Docker during build — documented as a
  requirement in developer docs; CI runners must support it.
- Scalar exposure in production is a later decision (dev-only for now).
- Rate-limiting implementation starts in-process (per instance); a distributed limiter is
  a later concern (single cell, single instance for MVP).
