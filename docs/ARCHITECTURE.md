# Architecture

## Overview

Verifolio is designed as a **deployable regional modular monolith**.

The system must support isolated regional deployments so that personal data, documents, files, audit logs, sessions, and workflow state can be stored and processed in the required jurisdiction.

Examples:

- Russian users can be stored and processed in a Russian deployment.
- EU users can be stored and processed in an EU deployment.
- Future regions can be added as separate application cells.

## Architectural Style

The backend is a **modular monolith**, not a microservice system.

This choice gives the project:

- strong domain boundaries;
- simple deployment;
- local transactional consistency;
- faster development;
- easier testing;
- easier AI-agent contribution;
- a migration path toward services later if necessary.

Module boundaries should be explicit and verified through Spring Modulith tests.

## High-Level System Diagram

```text
[Web App / Public Site]
        |
        | REST / OpenAPI
        v
[Kotlin Spring Boot Backend]
        |
        +--> [PostgreSQL]
        |
        +--> [S3-compatible Object Storage]
        |
        +--> [Temporal]
        |
        +--> [Mail Provider]
        |
        +--> [Optional Regional AI/OCR Providers]
```

## Regional Cell Architecture

Each region runs an isolated application cell:

```text
[EU Cell]
- Web application
- Backend API
- PostgreSQL
- S3-compatible object storage
- Temporal
- Mail provider
- Audit logs
- Monitoring/logging pipeline

[RU Cell]
- Web application
- Backend API
- PostgreSQL
- S3-compatible object storage
- Temporal
- Mail provider
- Audit logs
- Monitoring/logging pipeline
```

A regional cell must not depend on another regional cell for user data, documents, files, sessions, or audit events.

## Global Layer

A global layer may exist only for:

- public marketing pages;
- product documentation;
- non-personal static assets;
- region selection;
- deployment metadata that does not contain personal data.

The global layer must not store:

- user accounts;
- emails;
- phone numbers;
- sessions;
- documents;
- scans;
- signatures;
- audit logs;
- IP-based behavioral history;
- file metadata containing personal data.

## Backend Internal Structure

Recommended backend structure:

```text
apps/backend/src/main/kotlin/com/verifolio/
├── identity/
├── profiles/
├── organizations/
├── contacts/
├── requests/
├── templates/
├── documents/
├── files/
├── verification/
├── signatures/
├── workflows/
├── notifications/
├── audit/
└── admin/
```

Each module should follow a layered structure:

```text
module/
├── api/
├── application/
├── domain/
└── infrastructure/
```

## Dependency Rules

Modules must not freely access each other's internals.

Allowed communication:

- through public application services;
- through domain/application events;
- through explicit module API contracts;
- through read models if documented.

Forbidden communication:

- direct access to another module's repositories;
- direct modification of another module's tables;
- importing another module's internal/domain classes without an explicit boundary;
- bypassing application services for sensitive actions.

## Why Not Microservices Initially

Microservices are intentionally avoided in v1 because they would add:

- distributed transactions;
- complex deployment;
- service discovery;
- cross-service contracts;
- distributed debugging;
- network failure modes;
- harder local development;
- more difficult AI-agent contributions.

The product domain is still evolving, so a modular monolith provides better speed and control.

## Future Service Extraction Candidates

Possible future services:

- signature verification service;
- OCR/document processing service;
- PDF rendering service;
- regional AI assistant service;
- public verification gateway;
- analytics/event aggregation service.

These services should only be extracted when module boundaries and scaling needs justify it.
