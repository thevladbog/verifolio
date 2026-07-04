# Temporal Workflow Skill

## Use When

Use this skill when adding or changing long-running workflows, reminders, expiration, revocation, or background processes.

## Read First

- `docs/WORKFLOWS.md`
- `docs/AUDIT_EVENTS.md`
- `AGENTS.md`

## Rules

- Workflows orchestrate; application services enforce domain rules.
- Activities must be idempotent.
- Handle retries explicitly.
- Do not break in-flight workflows.
- Add audit events for sensitive workflow effects.

## Common Mistakes

- Putting all business logic inside workflow code.
- Non-idempotent email/file generation.
- Missing expiration/revocation paths.
- No workflow tests.

## Required Tests

- Happy path workflow test.
- Expiration/revocation test.
- Retry/failure test.
- Idempotency test for external effects.

## Done Checklist

- [ ] Workflow documented
- [ ] Activities idempotent
- [ ] Tests added
- [ ] Audit events added
- [ ] Versioning considered
