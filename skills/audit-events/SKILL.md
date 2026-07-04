# Audit Events Skill

## Use When

Use this skill when adding or changing sensitive operations that must be traceable.

## Read First

- `docs/AUDIT_EVENTS.md`
- `docs/SECURITY.md`
- `AGENTS.md`

## Rules

- Every sensitive action creates an audit event.
- Audit logs are append-only.
- Keep audit metadata safe and minimized.
- Do not store raw tokens or document contents in audit events.

## Common Mistakes

- Forgetting audit events for downloads/share links.
- Storing too much personal data.
- Making audit records mutable.

## Required Tests

- Audit event creation test.
- Metadata safety test where practical.
- Authorization/action test.

## Done Checklist

- [ ] Event type documented
- [ ] Event emitted
- [ ] Metadata safe
- [ ] Tests added
