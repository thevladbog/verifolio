# Regional Deployment & Data Residency

## Goal

Verifolio must be deployable as an isolated regional product.

The system must support deployments where all personal data and documents are stored and processed inside a selected jurisdiction.

Examples:

- Russian deployment for Russian users.
- EU deployment for EU users.
- Future country or region-specific deployments.

## Regional Cell

A **regional cell** is an isolated deployment of the Verifolio system.

Each regional cell contains:

- backend API;
- frontend application;
- PostgreSQL database;
- S3-compatible object storage;
- Temporal cluster/namespace;
- mail provider configuration;
- audit logs;
- monitoring/logging;
- optional OCR/AI services.

## Region Selection

During registration, the user must explicitly choose a data region.

Example UI copy:

```text
Where should your data be stored?

- European Union
- Russian Federation
- Other / Global

This determines where your personal data, documents, files, and audit records will be stored and processed.
```

IP-based region detection may be used only as a suggestion. It must not silently determine the storage region.

## Region Immutability

A user's region must not be changed automatically.

Region migration requires:

- explicit user consent;
- export/import process;
- audit trail;
- legal review;
- deletion or retention policy for the source region.

## Data That Must Stay Regional

The following data must stay inside the selected regional cell:

- user accounts;
- profile data;
- emails;
- phone numbers;
- sessions;
- magic link tokens;
- invitation tokens;
- requests;
- references;
- generated letters;
- scans;
- signatures;
- file metadata;
- audit logs;
- verification events;
- document hashes;
- workflow state;
- logs containing personal data.

## Cross-Region Restrictions

Forbidden by default:

- central global user database;
- central global auth database;
- central object storage bucket;
- central audit/event log with personal data;
- cross-region OCR/AI processing;
- sending user files to non-regional external providers;
- cross-region workers processing documents;
- centralized logs containing emails, IP addresses, file names, or document metadata.

## Global Marketing Site

The global site may host:

- marketing pages;
- public product information;
- static documentation;
- region selector;
- pricing pages;
- non-personal assets.

The global site must not store user-specific personal data.

## Public Verification Pages

Public verification pages must be served from the user's regional cell.

A share URL may include a region-specific domain:

```text
https://verify.eu.verifolio.example/v/...
https://verify.ru.verifolio.example/v/...
```

or a global router may redirect to the correct regional cell without storing personal data globally.

## External Providers

Any external provider must be configured per region.

Examples:

- mail provider;
- SMS provider;
- OCR provider;
- AI provider;
- signature verification provider;
- monitoring/logging provider.

If a region cannot legally or operationally use a provider, the related feature must be disabled or implemented locally.

## AI/OCR Processing Modes

Recommended configuration:

```text
AI_MODE:
- disabled
- local
- regional_provider
- external_provider_with_explicit_consent
```

Documents must not be sent to external AI/OCR services outside the required region unless explicitly designed and legally approved.

## Deployment Environments

Recommended environments per region:

```text
dev
staging
production
```

Staging must use non-production data unless legally and operationally approved.

## Operational Requirement

Every production incident, backup, log export, or support action must respect regional data boundaries.
