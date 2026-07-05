# Organizations Module — Design

Date: 2026-07-05
Status: approved (user picked organizations as the next v1.1 module)
Scope: iteration 12 — first real code in the `organizations` module, at the exact MVP
boundary MODULES.md draws: **"the Organization entity and domain records only; full
organization verification is post-MVP."** Deliver a curated verified-organization
registry + domain lookup, use it to strengthen the CORPORATE_DOMAIN_CONFIRMED signal
(recommender-stated → verified-record), a read API, and surface the verified org name on
the public verification page. Domain-ownership verification (email/DNS) and org
management UI are explicitly deferred to the admin module.

Normative sources: MODULES.md §organizations (58-67, MVP-scope line 67), DATA_MODEL.md
(Organization entity), VERIFICATION_SIGNALS.md §CORPORATE_DOMAIN_CONFIRMED (104-130 —
"organization name source: recommender-stated unless verified Organization record exists
for domain"), ROADMAP.md v1.1.

## Design decisions on the doc-unspecified items

1. **Ownership model — global reference registry, not user-owned.** Organizations are
   public reference data (company names + domains), not personal data; the table has no
   `owner_profile_id` (consistent with DATA_MODEL.md). They are managed centrally (by
   admin, deferred) and seeded per cell via Flyway now.
2. **Management path — seeded now, admin later.** No create/update/verify HTTP endpoints
   this iteration (there is no admin auth yet, and a global trust anchor must not be
   writable by arbitrary users). Verified organizations ship as a Flyway seed migration
   (the templates-in-V3 precedent). The admin module adds curation/verification UI.
3. **Domain verification mechanics — deferred.** No email-role-address or DNS-TXT
   ownership proof this iteration (that IS "full organization verification", post-MVP).
   `verification_status` on a seeded row is set by the curator; the app trusts the
   registry, it does not prove ownership.
4. **`verification_status` enum**: `UNVERIFIED | VERIFIED | REVOKED` (CHECK constraint
   in V12). PENDING is meaningless without a verification flow — omitted until it exists.
   Only VERIFIED rows strengthen the signal.
5. **Signal derivation — enrich, don't add a new signal type.** CORPORATE_DOMAIN_CONFIRMED
   stays the single signal; its evidence gains `organizationId` + `organizationName` +
   `organizationNameSource: "verified-record"` when a VERIFIED org's domains match, else
   the existing `"recommender-stated"`. No BadgeCatalog signal-type churn.
6. **Region-scoping — global, no region column.** Company names/domains are public,
   non-personal; the same registry is seeded in every cell. No cross-border concern (no
   personal data crosses). Noted so the RU cell seeds its own curated list.
7. **NAME_MATCH — still deferred.** Unrelated to orgs (needs a structured recipient-name
   field, PROFILE_VERIFICATION.md); not unblocked here.
8. **Contact ↔ org auto-linking — deferred.** `recommender_contact.organization_id`
   stays unpopulated; the signal keys off the recommender email domain at acceptance,
   not off contact linkage. Auto-linking is owner-scoped complexity for later.

## Schema (Flyway V12)

The `organization` table already exists (V2: `id, name, domains jsonb, verification_status,
created_at, updated_at`). V12 hardens and seeds it:

```sql
-- Constrain the status enum (V2 left it free text).
alter table organization
  add constraint organization_verification_status_check
  check (verification_status in ('UNVERIFIED','VERIFIED','REVOKED'));

-- Fast domain membership: a normalized side table beats JSONB containment for lookup
-- and enforces one-domain-one-verified-org.
create table organization_domain (
  id              uuid primary key default gen_random_uuid(),
  organization_id uuid not null references organization(id) on delete cascade,
  domain          text not null,
  created_at      timestamptz not null default transaction_timestamp()
);
create unique index organization_domain_domain_unique on organization_domain (lower(domain));
create index organization_domain_org_idx on organization_domain (organization_id);
```

Rationale for the side table: the lookup is "given an email domain, is there a VERIFIED
org that owns it?" — an indexed `lower(domain)` equality on a normalized row is simpler
and faster than JSONB array containment, and the unique index guarantees a domain maps to
at most one org (no ambiguous strengthening). The legacy `organization.domains` JSONB is
left in place (unused by new code) to keep V2 untouched; the seed writes both for
consistency, and DATA_MODEL notes `organization_domain` as the authoritative source.

### Seed (V13 data migration)

A small curated set of well-known VERIFIED organizations for the EU cell (e.g. a handful
of real large employers by public domain), mirroring the template-seed approach. Each:
one `organization` (VERIFIED) + its `organization_domain` rows. Kept intentionally short;
the real registry grows via admin curation. RU/GLOBAL cells seed their own lists later.

## organizations module

Public API (package root):

```kotlin
// com.verifolio.organizations
data class OrganizationMatch(
    val organizationId: UUID,
    val name: String,
    val matchedDomain: String,
)

interface OrganizationLookup {
    /** The VERIFIED organization owning [emailDomain] (suffix-aware), or null. */
    fun findVerifiedByDomain(emailDomain: String): OrganizationMatch?
}

data class OrganizationView(
    val id: UUID,
    val name: String,
    val domains: List<String>,
    val verificationStatus: String,
)
```

- `OrganizationLookupImpl` (organizations.application, `@Service internal`): normalizes
  the domain to lowercase, matches `organization_domain.domain` exactly OR the email
  domain being a subdomain (`emailDomain == d || emailDomain.endsWith(".$d")`), joined to
  an `organization` with `verification_status = 'VERIFIED'`. Returns the first match
  (unique index makes exact matches unambiguous; for subdomain matches, longest-domain
  wins).
- Read service for the API below.
- Module dependencies: `platform` (props/exceptions), `audit` only. **organizations
  depends on nothing else** (avoids cycles — requests/verification depend on it, not the
  reverse). No writes to other modules' tables.

## Signal strengthening (requests module)

`ReferenceRequestService` (CORPORATE_DOMAIN_CONFIRMED creation, ~line 481): when the
domain is not a free-email domain, call `organizationLookup.findVerifiedByDomain(domain)`:

```kotlin
val orgMatch = organizationLookup.findVerifiedByDomain(emailDomain)
val evidence = buildMap {
    put("emailDomain", emailDomain)
    if (orgMatch != null) {
        put("organizationNameSource", "verified-record")
        put("organizationId", orgMatch.organizationId.toString())
        put("organizationName", orgMatch.name)
    } else {
        put("organizationNameSource", "recommender-stated")
    }
}
verificationSignals.createVerified("REFERENCE_RESPONSE", responseId,
    "CORPORATE_DOMAIN_CONFIRMED", evidence)
```

- Snapshot semantics preserved: the org name is captured into the signal evidence at
  acceptance (gating moment), so a later registry change never mutates a locked
  attestation — same rule that bit the backend four times before.
- No behavioural change when no verified org matches (identical to today).

## Public verification page (publicpages)

The CORPORATE_DOMAIN_CONFIRMED badge on the public page currently shows a generic domain
line. When the signal evidence carries `organizationNameSource = "verified-record"`, the
page adds the verified organization name with a clear provenance label ("at {name} —
verified organization record"); when `recommender-stated`, it keeps the existing
"stated by recommender" framing. This reuses the evidence already on the signal (no new
lookups on the public path, no personal data). Implemented in the publicpages read model
that assembles badge display, reading the signal evidence it already loads.

## Read API (organizations module)

Authenticated (session) read endpoints — org data is non-sensitive but the API stays
behind auth like the rest of `/api/v1` app surface (public page uses signal evidence, not
this API):

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/organizations?query=` | Search by name/domain prefix (keyset cursor, page 50) — powers a future builder hint / directory |
| `GET /api/v1/organizations/{id}` | Fetch one `OrganizationView` (404 if absent) |
| `GET /api/v1/organizations/lookup?domain=` | `OrganizationView` for the VERIFIED owner of a domain, or 404 |

Reads are not audited (public reference data, no authorization boundary crossed —
templates precedent). No write endpoints (deferred to admin).

## Frontend (same PR, minimal)

- **Public verify page**: render the verified-organization name + provenance on the
  CORPORATE_DOMAIN_CONFIRMED badge when present (the only user-visible product change).
- **Request builder (optional, low-risk)**: when the chosen contact's email domain
  matches a VERIFIED org via `GET /organizations/lookup`, show a subtle "Recognised
  organization: {name}" hint on the recommender step. Non-blocking, informational.
- No org management UI (admin module).

## Audit events

New (AUDIT_EVENTS.md): none required for reads. The seed is a migration, not an audited
action. When admin adds management, it will register ORGANIZATION_CREATED/UPDATED/
DOMAIN_ADDED/VERIFIED — noted as deferred, not defined here to avoid unused catalog
entries.

## Open questions (resolved with recommendations; none block the plan)

1. **Longest-domain match for subdomains** — recommendation: if both `acme.com` and
   `eu.acme.com` are registered to different orgs, the longest matching suffix wins
   (most specific). The unique index prevents the same domain under two orgs. Implemented
   in the lookup ordering.
2. **Should the read API be public (permitAll)?** — recommendation: authenticated, to
   match the app surface and avoid an open directory-scraping endpoint; the public page
   needs no lookup (evidence is embedded). Revisit if a public org directory is wanted.
3. **Seed contents** — recommendation: 6–10 uncontroversial large employers by public
   primary domain, clearly a starter set; documented as curator-replaceable. No claim of
   completeness.

## Non-negotiables check

- Module boundaries: organizations depends only on platform/audit; requests/verification/
  publicpages depend on it. ModularityTests enforce (no cycle).
- Domain authorization not bypassed: the signal still requires the full acceptance path;
  the registry only labels provenance, it grants nothing.
- Locked/attested artifacts: org name snapshotted into signal evidence at gating time;
  registry edits never mutate existing signals.
- No object-storage URLs; no personal data added (company names/domains are public).
- Flyway V12 (constraint + side table) + V13 (seed); V1–V11 untouched.
- API change ships with OpenAPI update + regenerated frontend client (check:api) — and
  remember to `npm run gen:api` after the snapshot refresh.
- No new verification signal type; no new BadgeCatalog trust semantics (evidence enrich
  only) — VERIFICATION_SIGNALS.md documents the source distinction that already existed.
