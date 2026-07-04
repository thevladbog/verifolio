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
- Consent records are regional data: per-region consent texts apply, and processing a recommender's response always requires a recorded accept/decline.
- Cross-border recommender participation requires an explicit cross-border transfer consent record.
- Data subject requests (deletion, export, region migration) exist and must not be blocked; region migration moves the subject's data between regional cells per the documented erasure/tombstoning model.

## Common Mistakes

- Adding global analytics with PII.
- Sending documents to external AI/OCR.
- Centralizing logs with emails/IPs.
- Global user database.

## Required Verification

- Verify region policy configuration against `docs/REGION_POLICIES.md`.
- Verify the provider is blocked/allowed per region where applicable.
- Review the resulting data flows for cross-region movement, including consent records and cross-border transfer consent.
- Verify data subject request handling (deletion, export, region migration) is not broken by the change.

## Done Checklist

- [ ] Region policy checked
- [ ] No global PII
- [ ] Provider allowed
- [ ] Consent records handled (incl. cross-border transfer consent) where applicable
- [ ] DSR handling (deletion, export, region migration) not broken
- [ ] ADR added if data flow changed
- [ ] Tests/config checks added
