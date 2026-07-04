# Claude Code Instructions

This file adapts the canonical project rules from `AGENTS.md` for Claude Code.

## Required Reading

Before modifying code, follow the canonical reading list in `AGENTS.md` § Pre-Coding Reading List (it also defines how to scale reading with risk tier).

## Core Rules

The most catastrophic rules, inline:

- Never modify locked document versions.
- Never expose object storage URLs publicly.
- Never bypass domain authorization.
- Never move regional data (including consent records) out of its region or send it to non-regional providers.

The full non-negotiable list lives in `AGENTS.md` § Non-Negotiable Rules — read it before any change.

## Working Style

When implementing a task:

1. Identify affected modules.
2. Identify required data model/API/workflow changes.
3. Implement the smallest safe change.
4. Add or update tests.
5. Update docs if behavior changed.
6. Mention unresolved risks in the final summary.

`AGENTS.md` is the canonical rule source. If this file conflicts with `AGENTS.md`, follow `AGENTS.md`.
