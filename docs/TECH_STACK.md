# Technology Stack

## Backend

### Language

**Kotlin**

Reasons:

- strong static typing;
- null safety;
- expressive domain modeling;
- good Java ecosystem compatibility;
- suitable for long-lived document and verification workflows.

### Framework

**Spring Boot**

Reasons:

- mature production ecosystem;
- strong security support;
- transaction management;
- validation;
- scheduling/integration support;
- excellent testing ecosystem.

### Modularity

**Spring Modulith**

Used to define, document, and test module boundaries inside the modular monolith.

### API

**REST + OpenAPI**

Reasons:

- simple integration model;
- clear contracts;
- generated clients;
- easier QA and AI-agent implementation;
- better fit than GraphQL for workflow/document operations.

### Database

**PostgreSQL**

Reasons:

- transactional consistency;
- mature relational model;
- JSONB support for template answers and metadata;
- full-text search capabilities;
- strong operational maturity.

### Migrations

**Flyway**

All schema changes must be represented by Flyway migrations.

### Database Access

**jOOQ**

Reasons:

- type-safe SQL;
- explicit query control;
- no hidden ORM behavior;
- good fit for document/version/audit-heavy systems;
- schema-driven generated code.

## Object Storage

### Local Development

**MinIO**

MinIO is used as the local S3-compatible object storage implementation.

### Production

Any S3-compatible storage provider can be used per region:

- AWS S3;
- Cloudflare R2;
- Selectel / VK Cloud / Yandex Cloud for Russian deployments, if appropriate;
- Hetzner Object Storage;
- Backblaze B2;
- self-hosted MinIO, if operationally justified.

The application must depend on an internal `ObjectStorage` abstraction, not on a specific provider.

## Authentication

**Embedded Spring Security-based authentication module**

Initial auth model:

- email magic links;
- secure server-side sessions;
- recommender invitation links;
- optional password login later;
- optional social login later;
- optional OIDC provider integration later.

No Keycloak in v1.

## Workflow Engine

**Temporal**

Self-hosted per regional cell (see ADR 0005, `docs/adr/0005-workflow-engine.md`).

Used for long-running business processes:

- reference request lifecycle;
- reminders;
- expiration;
- document verification;
- signature verification;
- profile verification;
- share link expiration and revocation.

## Signature Verification

Signature verification is provider-based and region-specific (eIDAS providers in the EU; GOST R 34.10-2012 / CryptoPro-ecosystem providers in RU). See ADR 0007 (`docs/adr/0007-signature-verification-providers.md`).

## Local Development

**Docker Compose**

Local services:

- PostgreSQL;
- MinIO;
- Temporal;
- Mailpit;
- backend;
- frontend;
- optional Redis;
- optional local OCR/AI services.

## Frontend

Decided (see ADR 0006, `docs/adr/0006-frontend-stack.md`); **delivered (2026-07, `apps/frontend`)**:

- Next.js (App Router);
- React;
- TypeScript;
- Tailwind CSS (v4 `@theme` tokens from `docs/DESIGN_SYSTEM.md`);
- Radix UI / shadcn-style primitives (vendored);
- React Hook Form;
- Zod;
- TanStack Query;
- generated OpenAPI client (`openapi-typescript` + `openapi-fetch` from `apps/backend/api/openapi.yaml`);
- next-intl (en/ru);
- Vitest + React Testing Library (unit), Playwright (E2E).

The frontend is deployed per regional cell; public verification pages are rendered within the cell.

## Testing

- JUnit 5;
- Testcontainers;
- Spring Modulith tests;
- jOOQ integration tests;
- Temporal workflow tests;
- OpenAPI contract tests;
- security/authorization tests.

## Observability

Initial:

- structured JSON logs;
- request IDs;
- audit events;
- application metrics.

Later:

- OpenTelemetry;
- Prometheus;
- Grafana;
- Sentry or equivalent error tracking;
- region-local logging pipeline.
