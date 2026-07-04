# Branching and Pull Request Rules

## Branching

Recommended branch format:

```text
feature/<short-description>
fix/<short-description>
docs/<short-description>
infra/<short-description>
security/<short-description>
```

Examples:

```text
feature/reference-request-builder
fix/share-link-expiration
docs/verification-signals
security/token-hashing
```

## Pull Request Size

Prefer small PRs.

A PR should usually touch one feature or one module area.

Avoid combining:

- schema redesign;
- API changes;
- frontend implementation;
- workflow implementation;
- security changes;

in one large PR unless explicitly planned.

## Required PR Sections

`.github/PULL_REQUEST_TEMPLATE.md` is the authoritative definition of the required PR sections. Do not duplicate the section list here; update the template instead.

## Review Requirements

Security-sensitive PRs require explicit review for:

- auth;
- sessions;
- tokens;
- file access;
- public verification pages;
- regional data flow;
- AI/OCR integration;
- signature verification;
- audit event coverage.

## PR Labels

Recommended labels:

```text
area/backend
area/frontend
area/docs
area/security
area/infra
area/workflows
area/storage
area/auth
area/verification
area/regional
risk/high
needs-adr
agent-generated
```

## AI-Generated PRs

If an AI agent contributed significantly, the AI Contribution Disclosure must be filled in. It is embedded in `.github/PULL_REQUEST_TEMPLATE.md` (tool, model, human review performed).

## Merge Requirements

Before merge:

- CI passes (CI for the docs repo is defined in `.github/workflows/docs.yml`; application CI will be bootstrapped with the code repo, see `docs/TESTING.md` § CI Requirements);
- review comments resolved;
- docs updated;
- migrations reviewed;
- OpenAPI updated;
- security concerns addressed;
- ADR added if required.
