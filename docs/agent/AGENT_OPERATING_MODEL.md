# Agent Operating Model

## Purpose

This document defines how AI agents participate in Verifolio development.

## Agent Philosophy

AI agents are contributors, not architects of record.

They can implement within boundaries but must not silently change architecture, security, data residency, or domain rules.

## Agent Workflow

1. Read `AGENTS.md`.
2. Read relevant docs and skill packs.
3. Identify affected modules.
4. Identify risks.
5. Implement smallest safe change.
6. Add tests.
7. Update docs.
8. Summarize what changed and what remains uncertain.

## Agent Task Types

### Safe Tasks

- small UI component;
- DTO mapping;
- unit tests;
- documentation updates;
- simple API endpoint following existing pattern;
- adding a template following existing schema.

### Medium-Risk Tasks

- database migration;
- new domain status;
- new verification signal;
- new workflow step;
- file upload/download changes;
- public verification page changes.

### High-Risk Tasks

Require human review and often ADR:

- auth/session changes;
- cross-region data flow;
- external provider integration;
- signature verification architecture;
- document locking rules;
- object storage access policy;
- AI/OCR processing;
- audit model changes.

## Agent Stop Conditions

Agents must stop and ask for human decision if:

- requirements conflict;
- region policy is unclear;
- security impact is unclear;
- module boundary would be violated;
- data model change affects immutability;
- external provider would receive personal data;
- public display of sensitive data is requested.

## Required Final Summary

Every agent task should end with:

```markdown
## Completed

## Tests Added

## Docs Updated

## Risks / Open Questions

## Files Changed
```
