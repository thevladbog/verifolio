# Contributing

## Development Philosophy

Verifolio is a trust product. Code quality, security, auditability, and data residency are product features.

Contributions must preserve:

- domain correctness;
- regional data isolation;
- document immutability;
- file privacy;
- auditability;
- explicit API contracts;
- module boundaries.

## Contribution Flow

1. Create or pick an issue.
2. Clarify product and technical acceptance criteria.
3. Identify affected modules.
4. Check whether an ADR is needed.
5. Implement changes.
6. Add tests.
7. Update docs.
8. Open a pull request.
9. Pass review and CI.

## Before Opening a Pull Request

Run:

```bash
./gradlew test
./gradlew check
```

If frontend is included:

```bash
npm run lint
npm run test
npm run build
```

## Pull Request Description

Every PR should include:

```markdown
## Summary

## Affected Modules

## Domain Rules Considered

## Security / Authorization Impact

## Regional Data Impact

## Database Changes

## API Changes

## Tests

## Documentation Updates

## Risks / Follow-ups
```

## Documentation Requirements

Update documentation when changing:

- architecture;
- module boundaries;
- workflows;
- data model;
- verification signals;
- audit events;
- auth/session behavior;
- regional data behavior;
- object storage behavior;
- API contracts.

## ADR Requirements

Create an ADR when changing:

- backend stack;
- storage model;
- workflow engine;
- auth strategy;
- regional deployment model;
- external provider policy;
- AI/OCR processing;
- signature verification architecture;
- cross-region behavior.

## AI-Agent Contributions

AI agents may contribute code, tests, docs, and migrations, but must follow `AGENTS.md` and the relevant skill pack.
