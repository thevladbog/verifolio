# ADR 0001: Backend Stack

## Status

Accepted

## Context

Verifolio is a document-trust platform that requires:

- strong domain modeling;
- document immutability;
- auditability;
- regional deployments;
- file storage;
- digital signatures;
- long-running workflows;
- strict security rules.

## Decision

Use:

- Kotlin;
- Spring Boot;
- Spring Modulith;
- PostgreSQL;
- Flyway;
- jOOQ;
- Temporal;
- S3-compatible object storage.

## Consequences

Positive:

- strong backend foundation;
- explicit database control;
- production-ready ecosystem;
- testable module boundaries;
- suitable for AI-agent-assisted development.

Negative:

- more initial complexity than a lightweight TypeScript backend;
- requires Kotlin/Spring expertise;
- requires discipline in module design.
