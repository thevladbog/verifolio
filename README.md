# Verifolio Agent & Engineering Governance Pack

Generated: 2026-07-02

This package extends the initial Verifolio development documentation with operational rules for human developers and AI agents.

It focuses on:

- AI-agent operating rules;
- contribution workflow;
- Definition of Done;
- coding standards;
- pull request rules;
- release process;
- local development;
- product requirements;
- user flows;
- API design rules;
- audit events;
- verification signals;
- regional policies;
- privacy and data classification;
- skill packs for repeatable AI-agent tasks.

## Suggested Repository Placement

```text
/
├── AGENTS.md
├── CLAUDE.md
├── CONTRIBUTING.md
├── DEFINITION_OF_DONE.md
├── CODE_STYLE.md
├── BRANCHING_AND_PR_RULES.md
├── RELEASE_PROCESS.md
├── LOCAL_DEVELOPMENT.md
├── docs/
├── skills/
└── .cursor/rules/
```

## Start Here

For AI coding agents:

1. Read `AGENTS.md`.
2. Read `docs/agent/AGENT_OPERATING_MODEL.md`.
3. Read the relevant skill in `skills/*/SKILL.md`.
4. Read module-specific documentation from the main development docs package.
5. Implement only within documented boundaries.
6. Add tests and documentation updates.

## Highest-Priority Rules

1. Do not modify locked document versions.
2. Do not expose S3/MinIO object URLs publicly.
3. Do not bypass domain authorization.
4. Do not add database changes without Flyway migrations.
5. Do not change API contracts without OpenAPI updates.
6. Do not add sensitive actions without audit events.
7. Do not introduce cross-region data flows without region policy review and an ADR.
8. Do not create new verification signals without documenting semantics.
9. Do not implement auth/security/file handling changes without tests.
10. Do not let AI/OCR providers process regional data unless region policy explicitly allows it.
