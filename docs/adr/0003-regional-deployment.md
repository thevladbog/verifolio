# ADR 0003: Regional Deployment Model

## Status

Accepted

## Context

Verifolio must support storing and processing user data in required jurisdictions.

Examples:

- Russian user data in Russia;
- EU user data in the EU.

## Decision

Use isolated regional application cells.

Each region has its own:

- backend;
- database;
- object storage;
- workflow engine;
- auth/session storage;
- audit logs;
- mail provider;
- optional AI/OCR providers.

## Consequences

Positive:

- clearer data residency model;
- simpler compliance boundaries;
- regional operational isolation;
- easier provider selection per jurisdiction.

Negative:

- more deployment complexity;
- cross-region account migration requires a controlled process;
- global analytics and support tooling must be carefully designed.
