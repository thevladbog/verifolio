# Regional Deployment & Data Residency

## Goal

Verifolio must be deployable as an isolated regional product.

The system must support deployments where all personal data and documents are stored and processed inside a selected jurisdiction.

Examples:

- Russian deployment for Russian users.
- EU deployment for EU users.
- GLOBAL cell for users with no residency requirement.
- Future country or region-specific deployments.

Cells can launch sequentially (for example, EU first). See `docs/ROADMAP.md` for the staged rollout plan.

## Regional Cell

A **regional cell** is an isolated deployment of the Verifolio system.

Each regional cell contains:

- backend API;
- frontend application;
- PostgreSQL database;
- S3-compatible object storage;
- dedicated Temporal cluster (or at minimum a cluster whose persistence store is physically inside the region);
- mail provider configuration;
- audit logs;
- monitoring/logging;
- optional OCR/AI services.

Shared Temporal-cluster namespaces across cells are forbidden for cells with `dataResidency: required` — workflow state is regional data. See ADR 0005.

The current cells are:

- **EU** — hosted in the European Union;
- **RU** — hosted in the Russian Federation;
- **GLOBAL** — a normal regional cell hosted in a named jurisdiction (placeholder: EU-hosted infrastructure) for users with no residency requirement. It is distinct from the stateless global marketing/region-selection layer and offers weaker residency guarantees, as stated in the Terms of Service. See `docs/REGION_POLICIES.md`.

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

## Region Routing at Login

Users authenticate on region-specific app domains (for example `app.eu.verifolio.com`, `app.ru.verifolio.com`). The global layer only performs region selection; there is no global email→region directory in v1. See ADR 0008 (`docs/adr/0008-region-routing-at-login.md`).

## Region Immutability

A user's region must not be changed automatically.

Region migration requires:

- explicit user consent;
- export/import process;
- audit trail;
- legal review;
- deletion or retention policy for the source region.

## Region Migration

Region migration is a user-initiated **data subject request** (DSR).

Execution:

1. Export from the source cell.
2. Import into the target cell.
3. Verified deletion in the source cell.

Requirements:

- audited on both sides with `REGION_MIGRATION_STARTED`, `REGION_MIGRATION_COMPLETED`, and `REGION_MIGRATION_FAILED` events;
- locked document versions and their hashes are preserved across the migration;
- consent texts are re-accepted in the target region before the migration completes.

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

or a global verification router may redirect to the correct regional cell.

The global verification router is **stateless**: share-link and verification tokens encode the region (via a region-encoded token prefix or region subdomains), so routing requires no lookup and no personal data is stored globally. See ADR 0008.

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

## Monitoring & Logging

Monitoring and log pipelines are per-cell.

Log data containing personal data must not leave the region. Any centralized dashboards may consume only aggregated, non-personal metrics.

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
