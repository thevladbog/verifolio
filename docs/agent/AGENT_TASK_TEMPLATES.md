# Agent Task Templates

## Feature Implementation Prompt

```text
You are implementing a Verifolio feature.

Read:
- AGENTS.md
- DEFINITION_OF_DONE.md
- docs/MODULES.md
- docs/DATA_MODEL.md
- docs/SECURITY.md
- relevant skill packs

Task:
[describe feature]

Before coding:
1. Identify affected modules.
2. Identify DB/API/workflow changes.
3. Identify authorization checks.
4. Identify audit events.
5. Identify regional data impact.
6. Identify tests.

Implement the smallest safe change.
```

## Bug Fix Prompt

```text
You are fixing a Verifolio bug.

Read AGENTS.md and relevant module docs.

Bug:
[describe bug]

Required:
- reproduce or explain root cause;
- add regression test;
- fix without violating module boundaries;
- update docs if behavior changed.
```

## Documentation Prompt

```text
You are updating Verifolio documentation.

Read existing related docs first.

Goal:
[describe documentation update]

Keep docs concise, actionable, and aligned with AGENTS.md.
If architecture changes, create or update an ADR.
```

## Security Review Prompt

```text
You are performing a security review.

Focus on:
- authentication;
- authorization;
- token handling;
- file access;
- audit events;
- regional data flow;
- logging;
- public verification pages.

Output:
- findings;
- severity;
- recommended fixes;
- required tests.
```

## ADR Prompt

```text
Create an ADR for this decision:

Decision:
[decision]

Include:
- status;
- context;
- considered options;
- decision;
- consequences;
- follow-up tasks.
```
