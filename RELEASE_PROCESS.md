# Release Process

## Release Philosophy

Verifolio handles trust-sensitive documents. Releases must be traceable, reversible where possible, and region-aware.

## Environments

Recommended per region:

```text
dev
staging
production
```

Production releases are region-specific.

A release can be deployed to EU without deploying to RU, and vice versa.

## Release Checklist

Before release:

- [ ] CI green
- [ ] Database migrations reviewed
- [ ] Backward compatibility checked
- [ ] Feature flags configured
- [ ] Region-specific config reviewed
- [ ] External providers configured per region
- [ ] Security-sensitive changes reviewed
- [ ] Rollback plan documented
- [ ] Monitoring alerts checked
- [ ] Release notes prepared

## Database Migrations

Migrations must be:

- backward-compatible where possible;
- tested on staging;
- reviewed for lock/time impact;
- region-safe.

Avoid destructive migrations in the same release as code changes.

## Feature Flags

Use feature flags for:

- AI/OCR features;
- external providers;
- new verification signals;
- public verification page changes;
- signature verification providers;
- profile verification flows.

## Regional Release Notes

Release notes should specify:

- affected regions;
- new providers;
- data model changes;
- security changes;
- user-facing changes;
- migration notes.

## Rollback

Rollback plan must consider:

- code rollback;
- database migration rollback/forward fix;
- object storage changes;
- workflow compatibility;
- in-flight Temporal workflows.

## Temporal Workflows

Workflow changes must consider versioning.

Do not break in-flight workflows.

## Incident Response

For trust/security incidents, capture:

- affected region;
- affected users/documents;
- affected data categories;
- timeline;
- mitigation;
- follow-up tasks;
- audit event review.
