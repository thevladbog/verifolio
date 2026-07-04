# Definition of Done

This is the task closure checklist: a feature, bug fix, or technical change is done only when all applicable items below are complete. (The PR body self-check lives in `AGENTS.md`; the human reviewer checklist is `docs/agent/AGENT_REVIEW_CHECKLIST.md`.)

## Universal

These apply to every change.

### Product

- [ ] Acceptance criteria are satisfied.
- [ ] User-facing behavior is documented if needed.
- [ ] Edge cases are handled.
- [ ] Error states are defined.

### Architecture

- [ ] Module boundaries are respected.
- [ ] No forbidden cross-module dependencies were introduced.
- [ ] No architectural decision was made without ADR when required.
- [ ] No unnecessary infrastructure/service was introduced.

### Backend

- [ ] Domain logic is implemented in application/domain layers, not controllers.
- [ ] Controllers are thin.
- [ ] Idempotency is handled for retryable operations.

### Tests

- [ ] Unit tests added/updated.
- [ ] Integration tests added/updated.
- [ ] E2E tests added for critical flows where practical.

### Documentation

- [ ] Relevant docs updated.
- [ ] ADR added if needed.
- [ ] Agent skill docs updated if workflow changed.

### Final Check

- [ ] Local checks pass.
- [ ] CI passes.
- [ ] PR follows `.github/PULL_REQUEST_TEMPLATE.md` and includes risks/follow-ups.

## Conditional — check if your change touches X

### Auth / Tokens

- [ ] Auth/session impact reviewed.
- [ ] Authorization checks are present.
- [ ] Authorization tests added where applicable.
- [ ] Security/authorization tests added where applicable.
- [ ] Tokens are hashed at rest.
- [ ] Sensitive data is not logged.
- [ ] Rate limiting considered for public/token flows.

### Documents / Immutability

- [ ] Locked document versions cannot be modified.

### Files / Storage

- [ ] Files go through files module only.
- [ ] No public object storage URLs.
- [ ] File metadata and hash are stored.
- [ ] Upload/download authorization is checked.
- [ ] File type and size validation are present.

### Regional / Providers

- [ ] Region policy checked.
- [ ] No cross-region data flow introduced.
- [ ] External providers are allowed for the region.
- [ ] Logs/metrics do not leak personal data globally.
- [ ] No personal data moved to global/non-regional storage.

### Database / Migrations

- [ ] Flyway migration added if schema changed.
- [ ] jOOQ code regenerated if schema changed.
- [ ] Constraints/indexes added where needed.

### API Contracts

- [ ] OpenAPI updated if API changed.
- [ ] Error responses follow documented format.
- [ ] Generated client updated if required.
- [ ] Backward compatibility considered.

### Audit

- [ ] Sensitive actions create audit events.
- [ ] Audit events are defined.

### Consent / DSR

- [ ] Consent records are created for new consent-requiring flows (accept or decline is always recorded).
- [ ] Data subject request handling (deletion, export, region migration) is not broken by the change.

### Verification Signals

- [ ] Verification signal semantics are documented.
- [ ] Public display text is defined.
- [ ] Trust limitations are clear.

### Workflows

- [ ] Workflow tests added for Temporal changes.
