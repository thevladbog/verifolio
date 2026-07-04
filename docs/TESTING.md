# Testing Strategy

## Goals

The test strategy must make the project safe for human and AI-agent development.

Tests should protect:

- domain rules;
- module boundaries;
- database schema behavior;
- file access;
- workflows;
- authorization;
- document immutability;
- regional isolation.

## Test Types

### Unit Tests

Cover:

- domain value objects;
- status transitions;
- permission policies;
- trust signal calculations;
- document version rules;
- token expiration logic.

### Integration Tests

Use Testcontainers for:

- PostgreSQL;
- MinIO;
- Temporal where practical;
- mail service simulation.

Do not mock PostgreSQL for repository tests.

### Module Boundary Tests

Use Spring Modulith tests to verify:

- allowed dependencies;
- module boundaries;
- event publication;
- architectural consistency.

### API Contract Tests

OpenAPI must be checked in CI.

Tests should verify:

- API schema generation;
- DTO compatibility;
- error response structure;
- auth requirements.

### Workflow Tests

Each Temporal workflow must have tests for:

- happy path;
- expiration;
- revocation;
- retries;
- idempotency;
- failure compensation.

### Security Tests

Required areas:

- unauthorized access;
- link expiration;
- link revocation;
- file access policy;
- cross-profile isolation;
- session expiration;
- token reuse;
- locked version modification.

### Frontend Tests

- Component tests: Vitest + Testing Library.
- Playwright E2E tests for the critical user flows (see End-to-End Tests below).
- Axe-based accessibility checks on the public verification page.

### End-to-End Tests

Critical flows:

- user registration via magic link;
- create reference request;
- recommender opens invitation;
- recommender submits response;
- document is generated and locked;
- verification page is opened;
- file is downloaded through authorized link.

## Test Data

Use builders/factories for test data.

Avoid using real personal data in tests.

## CI Requirements

> Status: backend CI is configured in `.github/workflows/backend.yml` (build + full test suite including Testcontainers-based integration tests). Frontend CI remains future work. Docs CI already exists in `.github/workflows/docs.yml`. The requirements below describe the full target application CI.

A pull request must pass:

- compile;
- unit tests;
- integration tests;
- module boundary tests;
- OpenAPI validation;
- formatting/linting;
- security-critical tests for touched modules.

## AI-Agent Rule

AI agents must include or update tests for every behavior change.
