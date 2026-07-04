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
admin
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
- profile trust score;
- reference content.

### profiles

Owns:

- person profiles;
- profile verification state;
- public/private profile fields;
- professional links;
- profile trust signals.

### organizations

Owns:

- organization records;
- domains;
- company metadata;
- organization verification signals.

### contacts

Owns:

- recommender contacts;
- relationship metadata;
- contact invitation state;
- communication preferences.

### requests

Owns:

- reference request lifecycle;
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

- verification signals;
- trust summary;
- verification status;
- evidence metadata;
- verification page display model.

### signatures

Owns:

- detached signatures;
- signature file relationships;
- signature verification results;
- certificate metadata;
- region-specific signature providers.

### workflows

Owns:

- Temporal workflow definitions;
- long-running orchestration;
- reminders;
- expirations;
- background verification flows.

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

### admin

Owns:

- internal support tools;
- moderation;
- operational dashboards;
- controlled support actions.

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
