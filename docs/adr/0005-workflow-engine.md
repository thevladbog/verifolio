# ADR 0005: Workflow Engine

## Status

Accepted

## Context

Verifolio runs durable, long-running business processes:

- reference request lifecycle with reminders on day 0/3/7/14/21;
- request and share link expirations;
- asynchronous file validation;
- data subject request (DSR) execution;
- region migration (export → import → verified deletion);
- document, signature, and profile verification.

These processes span days or weeks, must survive restarts and deployments, and must be observable and testable.

Regional deployment (ADR 0003) requires that workflow state — which contains personal data references — stays inside the regional cell.

## Considered Options

1. **Temporal, self-hosted per regional cell.**
   Durable workflow engine with retries, timers, visibility tooling, and a strong testing story.
2. **Quartz / DB-backed scheduler.**
   Simple scheduled jobs plus hand-written state machines in PostgreSQL. Low operational cost, but retries, long timers, and multi-step compensation logic must all be built and maintained by hand.
3. **Spring Modulith events + transactional outbox.**
   Good for decoupled in-cell integration events, but not a durable long-running workflow engine; timers and multi-week processes still need a scheduler on top.

## Decision

Use **Temporal**, self-hosted in every regional cell.

Each cell runs its own Temporal cluster, or at minimum a cluster whose persistence store is physically inside the region. Shared-cluster namespaces across cells are forbidden for cells with `dataResidency: required`, because workflow state is regional data.

Temporal Cloud is not residency-appropriate for RU (and must be evaluated per jurisdiction elsewhere), so self-hosting is mandatory for the RU cell.

## Consequences

Positive:

- durable long-running workflows (reminders, expirations, DSR execution, region migration) without hand-written state machines;
- built-in retries, timers, and compensation patterns;
- visibility into in-flight workflows for operations and support;
- first-class workflow testing support.

Negative:

- heavy per-cell operational cost: every regional cell must run and maintain its own Temporal cluster;
- version-compatibility discipline required for in-flight workflows across deployments;
- additional infrastructure expertise required per region.

Fallback position: if the operational cost proves too high for the MVP, a DB-backed scheduler implemented behind the same application-service interfaces is the documented retreat path. Workflow logic must therefore stay behind application-level interfaces and not leak Temporal APIs into domain modules.

## Implementation Note (2026-07)

The MVP engaged the fallback: the `workflows` module ships a DB-backed scheduler
(`RecurringTask` public interface + a Spring TaskScheduler runner) and the reminder/
expiration/cleanup tasks are implemented as `RecurringTask` beans in their owning domain
modules. No Temporal APIs appear anywhere; the Temporal migration replaces the runner
behind the same interface. Temporal remains the target engine, and the docker-compose
Temporal services stay in place for that migration.
