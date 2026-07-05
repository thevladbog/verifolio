# Region Policies

## Purpose

Region policies define what providers, processing modes, and data flows are allowed in each deployment region.

## Core Rule

Personal data, documents, files, sessions, workflow state, and audit logs must remain inside the user's selected region unless explicitly approved.

## Region Registry

| Region code | Cell | Hosting jurisdiction | Data residency level | Allowed provider categories |
|-------------|------|----------------------|----------------------|-----------------------------|
| `EU` | EU cell | European Union | `required` | EU-regional storage, mail, signature (eIDAS), optional EU-regional AI/OCR |
| `RU` | RU cell | Russian Federation | `required` | RU-regional storage, mail, SMS, signature (GOST/CryptoPro), optional local AI/OCR |
| `GLOBAL` | GLOBAL cell | EU-hosted infrastructure (placeholder; named jurisdiction) | `named_jurisdiction` | Regional-first providers; listed non-regional providers allowed (see GLOBAL policy) |
| `local` | — | developer machine | none | anything (Docker Compose) |

`local` is a development-only value. It must never appear in production configuration.

## Consent Texts

Each region policy defines **versioned consent text identifiers**:

- **processing consent** — data-processing consent under the region's legal regime (152-FZ for RU, GDPR for EU);
- **cross-border transfer consent** — used when the data subject and the storing cell are in different jurisdictions (for example, a recommender in RU responding to a requester whose cell is in the EU);
- **public-sharing consent** — consent to publish content on public verification pages.

Rules:

- consent texts are versioned per region; any text change bumps the version;
- the copy itself ships with the deployment as classpath resources keyed by
  `consent-texts/{textId}/{version}/{locale}.md` and is served via
  `GET /api/v1/consent-texts/{consentType}` (public, read-only, locale falls back to
  `en`); per-cell config selects which textId:version is active, resources must be
  kept for every version still referenced by stored consent records, and startup
  fails fast if an active text lacks its `en` resource;
- ConsentRecords store the accepted text identifier and version;
- consent records and data subject requests (deletion, export, region migration, consent withdrawal, correction) are first-class records with per-region SLAs.

Example policy fragment:

```yaml
consents:
  processing:
    textId: eu-processing
    version: 3
  crossBorderTransfer:
    textId: eu-cross-border
    version: 1
  publicSharing:
    textId: eu-public-sharing
    version: 2
```

## Cross-Border Participation

When a recommender responds to a request stored in another region, the recommender's data (response, documents, signature files) is stored in the **requester's cell**.

Requirements:

- the recommender consent gate must collect the **cross-border transfer consent** of the storing cell's region policy before any data is stored;
- regional mail providers are used for all emails, in the region of the cell that sends them;
- the flow must be audited in the storing cell.

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
    - CAdES  # detached CMS/CAdES containers (.sig/.p7s)
    - PAdES  # embedded PDF signatures
```

RU signature notes:

- the signature format taxonomy is normalized across regions: detached CMS/CAdES containers and PAdES for embedded PDF signatures;
- legally meaningful RU signature verification requires GOST R 34.10-2012 cryptography and CryptoPro-ecosystem providers;
- when no verification provider is available, the system asserts `SIGNATURE_ATTACHED` only and never claims `SIGNATURE_VERIFIED`.

See ADR 0007 (`docs/adr/0007-signature-verification-providers.md`).

## GLOBAL Policy

GLOBAL is a **normal regional cell** hosted in a named jurisdiction (placeholder: EU-hosted infrastructure) for users with no residency requirement.

It is distinct from the stateless global marketing/region-selection layer, which stores no user data at all.

```yaml
region: GLOBAL
dataResidency: named_jurisdiction  # EU-hosted infrastructure (placeholder)
storage:
  regionLocal: true  # local to the GLOBAL cell
mail:
  regionLocal: true
ai:
  mode: listed_provider_with_explicit_consent
ocr:
  mode: listed_provider_with_explicit_consent
logs:
  centralPii: false
```

The GLOBAL cell relaxes exactly the following forbidden-by-default items:

- cross-border recommender participation is allowed with the cross-border transfer consent;
- external providers may be non-regional, but every provider must be explicitly listed in the policy and pass the provider checklist.

Everything else in "Forbidden By Default" still applies to the GLOBAL cell.

The Terms of Service must state that the GLOBAL cell offers weaker residency guarantees than the EU and RU cells.

## Temporal Residency

Each cell requires a dedicated Temporal cluster — or at minimum a cluster whose persistence store is physically inside the region.

Shared-cluster namespaces are forbidden for cells with `dataResidency: required`, because workflow state contains regional data. See ADR 0005 (`docs/adr/0005-workflow-engine.md`).

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

## Exceptions & Approvals

Any cross-region data flow or new external provider requires:

- an ADR documenting the flow or provider;
- approval by a named owner (see `CODEOWNERS`).

No exception may be implemented from a policy file change alone.

## AI-Agent Rule

Any code that calls external providers must check region policy.
