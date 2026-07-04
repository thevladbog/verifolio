# Agent Roles

## Must Read

Every role reads the role's primary skill pack(s) plus the canonical reading list in `AGENTS.md` § Pre-Coding Reading List. Skill packs own their own "read first" doc lists — this file does not duplicate them.

## Product Analyst Agent

Responsibilities:

- PRD refinement;
- acceptance criteria;
- user flows;
- request templates;
- copy;
- edge cases.

Primary skill packs: none (works from `docs/PRODUCT_REQUIREMENTS.md`, `docs/USER_FLOWS.md`, `docs/REQUEST_TEMPLATES.md` as module documentation).

## Backend Domain Agent

Responsibilities:

- Kotlin/Spring modules;
- domain policies;
- application services;
- status transitions;
- tests.

Primary skill packs: `skills/kotlin-spring-module/SKILL.md`.

## Database Agent

Responsibilities:

- Flyway migrations;
- jOOQ generation;
- constraints;
- indexes;
- data integrity.

Primary skill packs: `skills/postgres-jooq-flyway/SKILL.md`.

## Security Agent

Responsibilities:

- auth/session review;
- authorization;
- token handling;
- file access;
- threat model;
- privacy review.

Primary skill packs: `skills/security-auth/SKILL.md`.

## Workflow Agent

Responsibilities:

- Temporal workflows;
- reminders;
- expiration;
- retries;
- idempotency;
- workflow tests.

Primary skill packs: `skills/temporal-workflow/SKILL.md`.

## Frontend Agent

Responsibilities:

- Next.js app;
- request builder;
- dashboard;
- recommender form;
- verification page;
- API client integration.

Primary skill packs: `skills/frontend-nextjs/SKILL.md`.

## QA/Test Agent

Responsibilities:

- test plans;
- integration tests;
- authorization tests;
- E2E tests;
- regression checks.

Primary skill packs: `skills/testing-testcontainers/SKILL.md`.

## Documentation Agent

Responsibilities:

- docs updates;
- ADRs;
- onboarding;
- API docs;
- skill updates.

Primary skill packs: `skills/adr-writing/SKILL.md`.
