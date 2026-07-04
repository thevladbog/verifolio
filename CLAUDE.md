# Claude Code Instructions

This file adapts the canonical project rules from `AGENTS.md` for Claude Code.

## Required Reading

Before modifying code, read:

- `AGENTS.md`
- `docs/agent/AGENT_OPERATING_MODEL.md`
- the relevant `skills/*/SKILL.md`
- relevant module documentation

## Core Rules

- Never modify locked document versions.
- Never expose object storage URLs publicly.
- Never bypass domain authorization.
- Never add schema changes without Flyway migrations.
- Never change API contracts without OpenAPI updates.
- Never add sensitive actions without audit events.
- Never send regional data to non-regional providers.
- Never create new verification signals without documentation.
- Always add tests for behavior changes.

## Working Style

When implementing a task:

1. Identify affected modules.
2. Identify required data model/API/workflow changes.
3. Implement the smallest safe change.
4. Add or update tests.
5. Update docs if behavior changed.
6. Mention unresolved risks in the final summary.

`AGENTS.md` is the canonical rule source. If this file conflicts with `AGENTS.md`, follow `AGENTS.md`.
