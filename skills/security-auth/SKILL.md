# Security Auth Skill

## Use When

Use this skill when changing login, sessions, magic links, invitation tokens, permissions, or public access flows.

## Read First

- `docs/AUTHENTICATION.md`
- `docs/SECURITY.md`
- `docs/ERROR_HANDLING.md`
- `AGENTS.md`

## Rules

- Tokens must be hashed at rest.
- Sessions are server-side.
- Cookies must be secure/HTTP-only in production.
- Add rate limiting for token flows.
- Domain authorization lives in backend services.

## Common Mistakes

- Storing raw tokens.
- Logging tokens.
- Treating frontend checks as security.
- Confusing role checks with resource permissions.

## Required Tests

- Login test.
- Token expiration test.
- Token revocation/reuse test.
- Authorization tests.
- Rate-limit test where applicable.

## Done Checklist

- [ ] Tokens hashed
- [ ] Authorization checked
- [ ] Audit events added
- [ ] Tests added
- [ ] Sensitive logs avoided
