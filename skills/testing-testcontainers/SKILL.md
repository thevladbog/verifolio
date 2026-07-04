# Testing Testcontainers Skill

## Use When

Use this skill when adding integration tests, repository tests, storage tests, or workflow tests.

## Read First

- `docs/TESTING.md`
- `AGENTS.md`

## Rules

- Use Testcontainers for PostgreSQL/MinIO integration.
- Do not mock DB behavior for repository tests.
- Test authorization failures, not only success.
- Add regression tests for bug fixes.

## Common Mistakes

- Only testing happy path.
- Mocking too much.
- Missing authorization tests.
- Tests depending on local machine state.

## Required Tests

- Unit tests.
- Integration tests.
- Security/authorization tests.
- Workflow tests if Temporal changed.

## Done Checklist

- [ ] Relevant tests added
- [ ] Testcontainers used where needed
- [ ] Failure cases tested
- [ ] CI-safe
