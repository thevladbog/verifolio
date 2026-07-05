# Backend Modules

## Module List

The backend is organized as a modular monolith.

Recommended modules:

```text
identity
profiles
organizations
contacts
requests
templates
documents
files
verification
signatures
workflows
notifications
audit
privacy
admin
publicpages
```

## Module Responsibilities

### identity

Owns:

- user accounts;
- authentication identities;
- sessions;
- magic links;
- invitation tokens;
- login/logout;
- recommender access tokens.

Does not own:

- document permissions;
- profile trust summary;
- reference content.

### profiles

Owns:

- person profiles;
- profile verification state;
- public/private profile fields;
- professional links;
- profile trust summary presentation (consumes a read model of signals owned by `verification`; there is no numeric trust score).

### organizations

Owns:

- organization records;
- domains;
- company metadata;
- organization verification signals.

MVP scope delivered (iteration 12): the `Organization` entity, `organization_domain` records with a curated `VERIFIED` seed set, the `OrganizationLookup` domain-lookup API + authenticated read endpoints, and signal strengthening — a verified domain match upgrades `CORPORATE_DOMAIN_CONFIRMED` evidence from `recommender-stated` to `verified-record` (org id + name snapshotted at acceptance). Depends only on `platform`/`audit`; other modules (`requests`, `publicpages`) depend on it. Full organization verification and management (domain-ownership proof, contact↔org linking, curator/admin workflows) remain post-MVP via admin (see `docs/ROADMAP.md`).

### contacts

Owns:

- recommender contacts;
- relationship metadata;
- contact invitation state;
- communication preferences.

### requests

Owns:

- reference request lifecycle;
- reference responses;
- consent records;
- selected template;
- requester context;
- recommender invitation state;
- request status transitions.

### templates

Owns:

- request templates;
- question schemas;
- output schemas;
- required fields;
- template localization.

### documents

Owns:

- documents;
- document versions;
- generated letters;
- version locking;
- document status;
- public verification page core data.

### files

Owns:

- file metadata;
- object storage abstraction;
- upload/download flows;
- file hashes;
- file access policy.

### verification

Owns:

- verification signal records (single owner; other modules consume read models only);
- trust summary derivation (counts of confirmed signals by category — never a numeric score);
- verification status;
- evidence metadata;
- badge display texts (plain-language names and limitations per the signal catalog).

### publicpages

Composition layer for the public verification page (read-only). Owns:

- the public `/api/v1/verification-pages` endpoints;
- page assembly from the read models of `documents` (ShareLinkAccess), `requests`
  (RequestPublicView), `profiles`, and `verification` (signals, trust summary, badge texts);
- public page view/download audit emission.

Rationale: domain modules write signals INTO `verification` (documents → verification,
requests → verification), while the page must read FROM documents, requests, and
verification. Hosting the composition inside `verification` would create dependency
cycles; `publicpages` sits on top of the domain modules instead. Nothing may depend on
`publicpages`.

### signatures

Owns:

- detached signatures;
- signature file relationships;
- signature verification results;
- certificate metadata;
- region-specific signature providers.

### workflows

Owns only the shared workflow-engine infrastructure:

- under the ADR-0005 MVP fallback (current state): the `RecurringTask` public interface
  and the DB-backed scheduler runner;
- under Temporal (target state): Temporal client configuration, worker registration,
  shared workflow testing utilities.

Workflow definitions live in the owning domain modules (e.g. the reference request lifecycle workflow lives in `requests`, signature verification flows in `signatures`, reminders in the module that triggers them). `workflows` must contain no domain logic.

Allowed dependency edges:

- domain modules → `workflows` (to register workers and obtain the Temporal client);
- `workflows` → no domain module (it must not depend on domain modules).

### notifications

Owns:

- email notifications;
- reminder emails;
- transactional templates;
- provider abstraction.

### audit

Owns:

- audit events;
- sensitive action logging;
- append-only event records;
- actor/action/entity metadata.

### privacy

A small dedicated compliance module (chosen explicitly instead of placing this under `admin`, so that data subject handling is not coupled to internal support tooling).

Owns:

- data subject requests (DELETION, EXPORT, REGION_MIGRATION, CONSENT_WITHDRAWAL, CORRECTION);
- data subject request lifecycle and per-region SLA tracking;
- erasure/export orchestration entry points (delegating deletion of module data to the owning modules);
- intake for account holders and account-less recommenders.

### admin

Owns:

- internal support tools;
- moderation;
- operational dashboards;
- controlled support actions.

### platform

Owns cross-cutting technical concerns:

- configuration properties (`VerifolioProperties`);
- API error contract (`ApiError`, `GlobalExceptionHandler`);
- OpenAPI/Scalar wiring;
- web infrastructure (filter configuration, proxy awareness).

Must contain no domain logic. All domain modules may depend on its public API.

The `platform` module exists in code alongside the domain modules in `apps/backend`.

## Module Dependency Rules

Allowed:

- module API interfaces;
- application services;
- events;
- documented read models.

Forbidden:

- accessing another module's repositories directly;
- modifying another module's tables directly;
- importing another module's internal classes;
- bypassing authorization;
- bypassing audit events for sensitive actions.

## Suggested Internal Module Layout

```text
module/
├── api/
│   └── controllers, DTOs, public module interfaces
├── application/
│   └── use cases, services, commands, queries
├── domain/
│   └── entities, value objects, policies, domain events
└── infrastructure/
    └── repositories, external integrations, adapters
```

## Spring Modulith

Spring Modulith should be used to:

- document modules;
- verify boundaries;
- detect unwanted dependencies;
- generate architectural documentation;
- support event-based communication.

## AI-Agent Rule

AI agents must not create cross-module dependencies without updating this document and adding tests that verify module boundaries.
