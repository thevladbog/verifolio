# PostgreSQL jOOQ Flyway Skill

## Use When

Use this skill when changing schema, queries, repositories, indexes, constraints, or read models.

## Read First

- `docs/DATA_MODEL.md`
- `docs/TECH_STACK.md`
- `AGENTS.md`

## Rules

- Every schema change requires Flyway migration.
- Regenerate jOOQ classes after migrations.
- Prefer DB constraints for important invariants.
- Add indexes for owner/status/token lookups.
- Do not manually edit generated jOOQ code.

## Common Mistakes

- Updating entities without migration.
- Using nullable columns for required domain data.
- Missing indexes for public token lookup.
- Storing file contents in PostgreSQL.

## Required Tests

- Repository integration tests with PostgreSQL Testcontainers.
- Migration test.
- Constraint behavior test when relevant.

## Done Checklist

- [ ] Migration added
- [ ] jOOQ regenerated
- [ ] Tests added
- [ ] Indexes/constraints reviewed
- [ ] Data residency considered
