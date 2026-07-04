# Definition of Done

A feature, bug fix, or technical change is done only when all applicable items below are complete.

## Product

- [ ] Acceptance criteria are satisfied.
- [ ] User-facing behavior is documented if needed.
- [ ] Edge cases are handled.
- [ ] Error states are defined.

## Architecture

- [ ] Module boundaries are respected.
- [ ] No forbidden cross-module dependencies were introduced.
- [ ] No architectural decision was made without ADR when required.
- [ ] No unnecessary infrastructure/service was introduced.

## Backend

- [ ] Domain logic is implemented in application/domain layers, not controllers.
- [ ] Controllers are thin.
- [ ] Authorization checks are present.
- [ ] Sensitive actions create audit events.
- [ ] Idempotency is handled for retryable operations.
- [ ] Locked document versions cannot be modified.

## Database

- [ ] Flyway migration added if schema changed.
- [ ] jOOQ code regenerated if schema changed.
- [ ] Constraints/indexes added where needed.
- [ ] No personal data moved to global/non-regional storage.

## API

- [ ] OpenAPI updated if API changed.
- [ ] Error responses follow documented format.
- [ ] Generated client updated if required.
- [ ] Backward compatibility considered.

## Files

- [ ] Files go through files module only.
- [ ] No public object storage URLs.
- [ ] File metadata and hash are stored.
- [ ] Upload/download authorization is checked.
- [ ] File type and size validation are present.

## Security

- [ ] Auth/session impact reviewed.
- [ ] Authorization tests added where applicable.
- [ ] Tokens are hashed at rest.
- [ ] Sensitive data is not logged.
- [ ] Rate limiting considered for public/token flows.

## Regional Data

- [ ] Region policy checked.
- [ ] No cross-region data flow introduced.
- [ ] External providers are allowed for the region.
- [ ] Logs/metrics do not leak personal data globally.

## Verification

- [ ] Verification signal semantics are documented.
- [ ] Public display text is defined.
- [ ] Trust limitations are clear.
- [ ] Audit events are defined.

## Tests

- [ ] Unit tests added/updated.
- [ ] Integration tests added/updated.
- [ ] Security/authorization tests added where applicable.
- [ ] Workflow tests added for Temporal changes.
- [ ] E2E tests added for critical flows where practical.

## Documentation

- [ ] Relevant docs updated.
- [ ] ADR added if needed.
- [ ] Agent skill docs updated if workflow changed.

## Final Check

- [ ] Local checks pass.
- [ ] CI passes.
- [ ] PR includes risks/follow-ups.
