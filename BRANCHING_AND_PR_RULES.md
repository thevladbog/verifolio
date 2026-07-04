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

```markdown
## Summary

## Affected Modules

## Product Behavior

## Domain Rules

## Security / Authorization

## Regional Data Impact

## Database Changes

## API Changes

## Tests

## Documentation

## Risks / Follow-ups
```

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

If an AI agent contributed significantly, include:

```markdown
## AI Contribution

- Agent/tool used:
- Files changed by agent:
- Human review focus:
- Known uncertainties:
```

## Merge Requirements

Before merge:

- CI passes;
- review comments resolved;
- docs updated;
- migrations reviewed;
- OpenAPI updated;
- security concerns addressed;
- ADR added if required.
