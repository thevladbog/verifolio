# Agent Skills Index

## Purpose

Skill packs provide reusable instructions for common development tasks.

## Skills

```text
skills/kotlin-spring-module/SKILL.md
skills/postgres-jooq-flyway/SKILL.md
skills/openapi-contract/SKILL.md
skills/temporal-workflow/SKILL.md
skills/minio-s3-files/SKILL.md
skills/security-auth/SKILL.md
skills/audit-events/SKILL.md
skills/verification-signals/SKILL.md
skills/regional-data-residency/SKILL.md
skills/frontend-nextjs/SKILL.md
skills/testing-testcontainers/SKILL.md
skills/adr-writing/SKILL.md
```

## When to Use Skills

Agents must use a skill when the task touches that area.

Examples:

- adding a DB column → `postgres-jooq-flyway`;
- adding a new endpoint → `openapi-contract`;
- changing file upload → `minio-s3-files`;
- adding reminder flow → `temporal-workflow`;
- adding signature signal → `verification-signals`;
- adding OCR provider → `regional-data-residency`;
- changing login/session → `security-auth`.

## Skill Format

Each skill includes:

- when to use;
- files to read first;
- rules;
- common mistakes;
- required tests;
- done checklist.
