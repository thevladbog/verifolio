# Verifolio Agent & Engineering Governance Pack

Generated: 2026-07-02

Status: governance and specification pack; application code has not been bootstrapped yet. Operational docs (`LOCAL_DEVELOPMENT.md`, `CONTRIBUTING.md` commands) describe the target state.

This repository contains the operational rules for human developers and AI agents, together with the Verifolio module documentation: `docs/` in this repository is the module documentation (architecture, data model, security, modules, workflows, and related specifications).

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
2. Follow the canonical reading list in `AGENTS.md` § Pre-Coding Reading List.
3. Read the relevant skill in `skills/*/SKILL.md`.
4. Read the module-specific documentation in `docs/`.
5. Implement only within documented boundaries.
6. Add tests and documentation updates.

## Highest-Priority Rules

The single canonical rule list lives in `AGENTS.md` § Non-Negotiable Rules. Headline examples:

1. Do not modify locked document versions.
2. Do not expose S3/MinIO object URLs publicly.
3. Do not bypass domain authorization.
4. Do not send regional user data to non-regional providers.
