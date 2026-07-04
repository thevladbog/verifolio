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

Used for long-running business processes:

- reference request lifecycle;
- reminders;
- expiration;
- document verification;
- signature verification;
- profile verification;
- share link expiration and revocation.

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

Recommended:

- Next.js;
- React;
- TypeScript;
- Tailwind CSS;
- Radix UI / shadcn-style primitives;
- React Hook Form;
- Zod;
- TanStack Query;
- generated OpenAPI client.

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
