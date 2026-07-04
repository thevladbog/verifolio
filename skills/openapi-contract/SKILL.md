# OpenAPI Contract Skill

## Use When

Use this skill when adding or changing API endpoints, DTOs, error responses, or generated clients.

## Read First

- `docs/API_GUIDELINES.md`
- `docs/ERROR_HANDLING.md`
- `AGENTS.md`

## Rules

- Update OpenAPI for every API change.
- Do not expose domain entities directly.
- Use explicit error codes.
- Use pagination for lists.
- Keep DTOs stable.

## Common Mistakes

- Creating undocumented endpoints.
- Returning internal storage keys.
- Returning inconsistent errors.
- Changing DTOs without updating frontend client.

## Required Tests

- API contract tests.
- Controller tests.
- Error response tests.
- Authorization tests.

## Done Checklist

- [ ] OpenAPI updated
- [ ] Generated client updated if needed
- [ ] Errors documented
- [ ] Tests added
