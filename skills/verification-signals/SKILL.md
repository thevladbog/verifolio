# Verification Signals Skill

## Use When

Use this skill when adding, changing, calculating, or displaying verification signals.

## Read First

- `docs/VERIFICATION_SIGNALS.md`
- `docs/PUBLIC_VERIFICATION_PAGE.md`
- `docs/AUDIT_EVENTS.md`
- `AGENTS.md`

## Rules

- Never use vague verified=true.
- Define meaning, evidence, provider, timestamp, expiration, public text, and limitation.
- Add audit events for signal creation/update.
- Public pages must show evidence and limitations.

## Common Mistakes

- Adding signal without documentation.
- Overstating trust.
- Missing expiration/revocation behavior.
- No public display semantics.

## Required Tests

- Signal creation test.
- Public display model test.
- Audit event test.
- Expiration/revocation test if applicable.

## Done Checklist

- [ ] Signal documented
- [ ] Evidence model defined
- [ ] Public text defined
- [ ] Audit event added
- [ ] Tests added
