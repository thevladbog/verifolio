# AGENTS.md

## Project

Verifolio is a regional, deployable platform for verified professional references, recommendation letters, signed documents, and professional proof portfolios.

The product verifies three trust dimensions:

1. Who the document is for.
2. Who confirmed the document.
3. Whether the document has changed.

## Technical Baseline

- Backend: Kotlin + Spring Boot
- Architecture: modular monolith
- Module boundaries: Spring Modulith
- Database: PostgreSQL
- Migrations: Flyway
- DB access: jOOQ
- Object storage: S3-compatible abstraction
- Local object storage: MinIO
- Workflows: Temporal
- API: REST + OpenAPI
- Auth: embedded Spring Security module with server-side sessions and email magic links
- Testing: JUnit 5 + Testcontainers

## Non-Negotiable Rules

- Do not modify locked document versions.
- Do not expose object storage URLs publicly.
- Do not bypass domain authorization.
- Do not call object storage outside the files module.
- Do not add database changes without Flyway migrations.
- Do not manually edit generated jOOQ code.
- Do not change API contracts without updating OpenAPI.
- Do not add sensitive actions without audit events.
- Do not add verification signals without documenting their semantics.
- Do not send regional user data to non-regional providers.
- Do not add cross-region data flows without an ADR.
- Do not implement auth/security/file changes without tests.
- Do not make recommenders create accounts unless a product requirement explicitly says so.
- Do not use AI-generated text as a verification source.

## Before Coding

Read:

- `docs/ARCHITECTURE.md`
- `docs/MODULES.md`
- `docs/DATA_MODEL.md`
- `docs/SECURITY.md`
- `docs/agent/AGENT_OPERATING_MODEL.md`
- relevant `skills/*/SKILL.md`
- relevant module documentation

## Required For Every Change

Every meaningful code change must include:

- tests;
- authorization checks where applicable;
- audit event updates where applicable;
- documentation updates when behavior changes;
- Flyway migration when DB schema changes;
- OpenAPI update when API changes;
- ADR when architecture or regional policy changes.

## Pull Request Checklist

- [ ] Module boundaries respected
- [ ] Domain rules preserved
- [ ] Tests added or updated
- [ ] Authorization rules checked
- [ ] Audit events added for sensitive actions
- [ ] Region/data residency rules checked
- [ ] OpenAPI updated if API changed
- [ ] Flyway migration added if DB changed
- [ ] Documentation updated if behavior changed
- [ ] No public object storage URLs
- [ ] No locked document mutation
- [ ] No plaintext token logging
- [ ] No external AI/OCR processing without region policy

## Definition of Done

A task is not done until it satisfies `DEFINITION_OF_DONE.md`.

## If Unsure

If the agent is uncertain about architecture, security, data residency, or domain rules, it must stop and ask for clarification or create a proposed ADR rather than guessing.
