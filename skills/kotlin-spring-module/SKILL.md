# Kotlin Spring Module Skill

## Use When

Use this skill when adding or changing backend modules, application services, domain policies, or controllers.

## Read First

- `AGENTS.md`
- `docs/MODULES.md`
- `docs/DATA_MODEL.md`
- `docs/SECURITY.md`

## Rules

- Respect module boundaries.
- Keep controllers thin.
- Put orchestration in application services.
- Keep invariants in domain/policies.
- Do not import another module's internals.
- Emit audit events for sensitive actions.

## Common Mistakes

- Putting business logic in controllers.
- Creating a God service.
- Accessing another module's repository.
- Returning domain entities directly from APIs.

## Required Tests

- Unit tests for domain rules.
- Application service tests.
- Authorization tests where applicable.
- Module boundary tests if dependencies changed.

## Done Checklist

- [ ] Module boundary respected
- [ ] Domain rules tested
- [ ] Authorization checked
- [ ] Audit events added
- [ ] Docs updated if behavior changed
