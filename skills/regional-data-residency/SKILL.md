# Regional Data Residency Skill

## Use When

Use this skill when adding providers, AI/OCR, logs, analytics, exports, imports, cross-region flows, or storage behavior.

## Read First

- `docs/REGIONAL_DEPLOYMENT.md`
- `docs/REGION_POLICIES.md`
- `docs/PRIVACY_AND_DATA_CLASSIFICATION.md`
- `AGENTS.md`

## Rules

- Personal data remains in selected region.
- Auth/session data is regional.
- Files are region-local.
- Logs containing PII are region-local or minimized.
- External providers require region policy approval.

## Common Mistakes

- Adding global analytics with PII.
- Sending documents to external AI/OCR.
- Centralizing logs with emails/IPs.
- Global user database.

## Required Tests

- Region policy test/config check.
- Provider-blocked test where applicable.
- Data flow review.

## Done Checklist

- [ ] Region policy checked
- [ ] No global PII
- [ ] Provider allowed
- [ ] ADR added if data flow changed
- [ ] Tests/config checks added
