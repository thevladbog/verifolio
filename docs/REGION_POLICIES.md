# Region Policies

## Purpose

Region policies define what providers, processing modes, and data flows are allowed in each deployment region.

## Core Rule

Personal data, documents, files, sessions, workflow state, and audit logs must remain inside the user's selected region unless explicitly approved.

## Region Policy Model

Recommended config shape:

```yaml
region: EU
storage:
  allowedProviders:
    - eu-s3-provider
mail:
  allowedProviders:
    - eu-mail-provider
ai:
  mode: regional_provider
ocr:
  mode: regional_provider
logs:
  piiAllowed: false
signatures:
  allowedProviders:
    - eu-signature-provider
```

## EU Policy Example

```yaml
region: EU
dataResidency: required
storage:
  regionLocal: true
mail:
  regionLocal: true
ai:
  mode: disabled_or_regional_provider
ocr:
  mode: disabled_or_regional_provider
logs:
  centralPii: false
signatures:
  allowedFormats:
    - PAdES
    - CAdES
```

## RU Policy Example

```yaml
region: RU
dataResidency: required
storage:
  regionLocal: true
mail:
  regionLocal: true
sms:
  regionLocal: true
ai:
  mode: disabled_or_local
ocr:
  mode: disabled_or_local
logs:
  centralPii: false
signatures:
  allowedFormats:
    - detached_sig
    - p7s
    - cades
```

## Global Policy Example

```yaml
region: GLOBAL
dataResidency: best_effort
storage:
  regionLocal: configurable
ai:
  mode: external_provider_with_explicit_consent
logs:
  centralPii: minimized
```

## Provider Checklist

Before adding a provider:

- What data is sent?
- Does it contain personal data?
- Does it contain document contents?
- Where is it processed?
- Where is it stored?
- Are logs retained?
- Is user consent required?
- Is there a regional alternative?
- Can the feature be disabled per region?

## Forbidden By Default

- Global PII analytics.
- Cross-region OCR without approval.
- Cross-region AI processing without approval.
- Centralized logs containing email/IP/document names.
- Global object storage buckets.
- Shared auth database across regions.

## AI-Agent Rule

Any code that calls external providers must check region policy.
