# AI Agent Development Guide

## Purpose

This project is expected to be developed with help from AI agents.

AI agents are allowed to write code, tests, documentation, migrations, and UI components, but they must follow strict architectural rules.

## General Rules

1. Do not bypass module boundaries.
2. Do not create cross-module dependencies without documenting them.
3. Do not access another module's repositories directly.
4. Do not change API contracts without updating OpenAPI.
5. Do not change database schema without Flyway migration.
6. Do not modify locked document versions.
7. Do not expose files through public object storage URLs.
8. Do not store personal data in global services.
9. Do not send regional user data to non-regional providers.
10. Do not implement security-sensitive changes without tests.

## Required Before Coding

Before implementing a feature, identify:

- affected modules;
- domain entities;
- API endpoints;
- database migrations;
- workflow changes;
- authorization rules;
- audit events;
- tests required.

## Required Output for Feature Work

Every feature PR should include:

- code;
- tests;
- migration if schema changed;
- OpenAPI update if API changed;
- documentation update if architecture/domain changed;
- audit events if sensitive actions were added;
- ADR if a significant decision was made.

## Forbidden Patterns

Do not:

- put business logic in controllers;
- let frontend decide permissions;
- call object storage directly from non-files modules;
- silently update confirmed reference content;
- use raw tokens in logs;
- store tokens in plaintext;
- use global mutable state for region-specific logic;
- hardcode production provider credentials;
- implement a new workflow without tests;
- create AI/OCR processing without region policy checks.

## Backend Coding Rules

Controllers should be thin.

Application services should contain use-case orchestration.

Domain objects should enforce invariants where practical.

Repositories should be module-local.

Sensitive operations must emit audit events.

## Database Rules

All schema changes require Flyway migrations.

jOOQ generated classes must be updated after schema changes.

Never manually edit generated jOOQ code.

## File Handling Rules

Only the files module can talk to object storage.

All files must have metadata and a hash.

All downloads require authorization.

## Auth Rules

Authentication identifies the actor.

Domain authorization decides access.

Do not confuse role checks with resource permissions.

Recommender invitation access must be scoped to a specific request.

## Verification Rules

Verification signals must be explicit.

A signal must describe:

- what was verified;
- how it was verified;
- when it was verified;
- by which provider/process;
- whether it expires.

Do not create vague signals such as `verified = true`.

## Documentation Rules

Update documentation when:

- adding a module;
- adding a workflow;
- adding a verification signal;
- adding a file type;
- changing auth;
- changing regional data behavior;
- changing document immutability rules.

## Recommended Prompt for AI Agents

```text
You are working on Verifolio, a Kotlin/Spring Boot modular monolith for verified professional references and proof documents.

Before coding, read:
- docs/ARCHITECTURE.md
- docs/MODULES.md
- docs/DATA_MODEL.md
- docs/SECURITY.md
- docs/AI_AGENT_GUIDE.md

Follow module boundaries.
Use Flyway for schema changes.
Use jOOQ for database access.
Use tests for every behavior change.
Do not expose files publicly.
Do not modify locked document versions.
Do not send regional data to non-regional providers.
```
