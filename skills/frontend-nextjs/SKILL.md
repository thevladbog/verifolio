# Frontend Next.js Skill

## Use When

Use this skill when building Next.js screens, forms, generated API client integration, or UI components.

## Read First

- `docs/USER_FLOWS.md`
- `docs/API_GUIDELINES.md`
- `docs/PUBLIC_VERIFICATION_PAGE.md`
- `AGENTS.md`

## Rules

- Use generated API client when available.
- Do not implement permissions only in frontend.
- Keep forms validated with shared/schema validation.
- Treat public verification page as trust-critical.
- Do not display sensitive fields without product requirement.

## Common Mistakes

- Hardcoding API types.
- Hiding buttons as the only security.
- Showing private file URLs.
- Overstating verification status.

## Required Tests

- Component tests where useful.
- Form validation tests.
- E2E tests for critical flows.
- API integration tests/mocks following OpenAPI.

## Done Checklist

- [ ] API client used
- [ ] Validation added
- [ ] Sensitive data checked
- [ ] UI states handled
- [ ] Tests added
