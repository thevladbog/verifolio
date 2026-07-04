# Agent Review Checklist

## General

- [ ] Agent read relevant docs.
- [ ] Agent used relevant skill pack.
- [ ] Change is scoped.
- [ ] No undocumented architectural decisions.

## Architecture

- [ ] Module boundaries respected.
- [ ] No direct repository access across modules.
- [ ] No unnecessary new service/dependency.
- [ ] ADR added if needed.

## Domain

- [ ] Domain invariants preserved.
- [ ] Locked document versions are not modified.
- [ ] Verification signal semantics are clear.
- [ ] Status transitions are valid.

## Security

- [ ] Authorization checks present.
- [ ] Tokens are handled safely.
- [ ] No sensitive data in logs.
- [ ] File access is safe.
- [ ] Public verification page does not leak private data.

## Regional Data

- [ ] No cross-region data flow.
- [ ] External providers checked.
- [ ] Logs/metrics policy respected.

## Testing

- [ ] Unit tests added.
- [ ] Integration tests added if needed.
- [ ] Authorization tests added if needed.
- [ ] Workflow tests added if needed.
- [ ] Regression tests added for bug fixes.

## Docs

- [ ] OpenAPI updated if API changed.
- [ ] Flyway migration included if DB changed.
- [ ] Docs updated if behavior changed.
- [ ] Skill docs updated if workflow/rules changed.
