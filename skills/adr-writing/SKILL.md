# ADR Writing Skill

## Use When

Use this skill when a task changes architecture, provider choices, security model, regional behavior, or core domain rules.

## Read First

- `docs/adr/`
- `AGENTS.md`
- related docs

## Rules

- ADRs must be concise.
- Include context, considered options, decision, consequences.
- Mark status.
- Link follow-up tasks if needed.

## Common Mistakes

- Writing decisions only in chat/PR comments.
- No alternatives considered.
- No consequences documented.

## Required Verification

- Review the ADR against the format rules above (context, options, decision, consequences, status).
- Check that the ADR is linked from relevant docs if important.
- Check that the ADR number does not collide with existing ADRs in `docs/adr/`.

## Done Checklist

- [ ] ADR added
- [ ] Status set
- [ ] Consequences documented
- [ ] Related docs updated
