# Organizations Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the `organizations` module at the MODULES.md MVP boundary — a curated verified-organization registry + domain lookup that strengthens CORPORATE_DOMAIN_CONFIRMED (recommender-stated → verified-record), a read API, and the verified org name on the public page.

**Architecture:** organizations owns the registry + `OrganizationLookup` public API (depends only on platform/audit); requests calls the lookup at acceptance to enrich the signal evidence; publicpages renders the provenance from that evidence. Seed via Flyway. Spec: `docs/superpowers/specs/2026-07-05-organizations-design.md` (design decisions there are normative).

**Tech Stack:** existing backend (Kotlin/Spring/jOOQ) + frontend (Next.js/openapi-fetch); no new dependencies.

## Global Constraints

- Migrations V1–V11 immutable; new schema = `V12__organization_domains.sql` (constraint + side table) and `V13__seed_organizations.sql` (data). jOOQ regen after.
- organizations module depends ONLY on platform + audit — no dependency on requests/verification/documents (they depend on it). ModularityTests must stay green.
- `verification_status` enum = UNVERIFIED | VERIFIED | REVOKED; only VERIFIED strengthens the signal.
- Signal evidence enrichment only — no new signal type, no BadgeCatalog trust-semantics change. Org name snapshotted into evidence at acceptance (gating-moment snapshot rule).
- No write/management endpoints (deferred to admin). Reads not audited (templates precedent).
- OpenAPI refreshed + `npm run gen:api` in apps/frontend committed (check:api gate — this bit us twice; do it).
- Integration tests extend `testsupport.IntegrationTest`; no sleeps.
- Frontend strings en+ru.

---

### Task 1: V12 + V13 seed + jOOQ

**Files:** Create `apps/backend/src/main/resources/db/migration/V12__organization_domains.sql`, `V13__seed_organizations.sql`; Modify `docs/DATA_MODEL.md` (organization_domain authoritative source, status enum).

- [ ] **Step 1:** V12 exactly per spec §Schema (status CHECK constraint + `organization_domain` table + unique `lower(domain)` index + org fk index).
- [ ] **Step 2:** V13 seeds 6–10 VERIFIED organizations, each an `organization` row (verification_status VERIFIED) + `organization_domain` rows (also mirror into `organization.domains` jsonb for consistency). Use `gen_random_uuid()` or fixed UUIDs; real public primary domains; a header comment marking it a curator-replaceable starter set.
- [ ] **Step 3:** `cd apps/backend && ./gradlew generateJooq compileKotlin` OK; verify `ORGANIZATION_DOMAIN` table generated. Commit — `feat(backend): V12 organization domains + V13 verified-org seed`

---

### Task 2: organizations module — lookup + read API

**Files:** Create `organizations/OrganizationLookup.kt` (+ data classes `OrganizationMatch`, `OrganizationView`), `organizations/application/OrganizationLookupImpl.kt`, `organizations/application/OrganizationQueryService.kt`, `organizations/api/OrganizationController.kt` + DTOs; Modify `docs/API_GUIDELINES.md`; Test `organizations/OrganizationIntegrationTest.kt`, `organizations/OrganizationLookupTest.kt`.

**Interfaces (produces):** `OrganizationLookup.findVerifiedByDomain(emailDomain: String): OrganizationMatch?` (suffix-aware, longest-match wins, VERIFIED only); read endpoints `GET /api/v1/organizations?query=&cursor=`, `/{id}`, `/lookup?domain=`.

- [ ] **Step 1:** `OrganizationLookupImpl` — normalize lowercase; query `organization_domain` join `organization` where status=VERIFIED and (`domain = emailDomain` or `emailDomain LIKE '%.'||domain`); order by `length(domain) desc` limit 1 → `OrganizationMatch`. Unit/integration test: exact match, subdomain match, longest-wins between `acme.com` and `eu.acme.com`, UNVERIFIED not matched, free-domain-like input no match, unknown domain null.
- [ ] **Step 2:** `OrganizationQueryService` + controller: search (name/domain prefix, keyset cursor page 50 — reuse the contacts cursor pattern), get-by-id (404), lookup-by-domain (maps `findVerifiedByDomain` → `OrganizationView` or 404). Authenticated (`@AuthenticationPrincipal AuthenticatedUser`). `OrganizationView` includes domains from `organization_domain`.
- [ ] **Step 2 tests:** integration — list returns seeded orgs + cursor; get-by-id; lookup by exact + subdomain domain; lookup 404 for unverified/unknown; unauthenticated → 401.
- [ ] **Step 3:** ModularityTests green (organizations → platform/audit only). Commit — `feat(backend): organizations lookup and read API`

---

### Task 3: strengthen CORPORATE_DOMAIN_CONFIRMED (requests)

**Files:** Modify `requests/application/ReferenceRequestService.kt` (inject `OrganizationLookup`, enrich evidence), `docs/VERIFICATION_SIGNALS.md` (document the verified-record source), `docs/MODULES.md` (mark organizations MVP delivered); Test extend `requests/*IntegrationTest` (the acceptance/signal test).

- [ ] **Step 1:** At CORPORATE_DOMAIN_CONFIRMED creation, look up the verified org and build evidence per spec (`organizationNameSource` verified-record + organizationId + organizationName when matched; recommender-stated otherwise). No behaviour change when unmatched.
- [ ] **Step 2: Tests** — accept a response whose recommender email domain matches a seeded VERIFIED org → signal evidence has verified-record + organizationId + organizationName; accept one with a non-seeded corporate domain → recommender-stated (unchanged); free-email domain → no CORPORATE_DOMAIN_CONFIRMED at all (unchanged). Assert the org name is a snapshot (evidence value equals the seed name).
- [ ] **Step 3:** Green; commit — `feat(backend): strengthen corporate-domain signal with verified org registry`

---

### Task 4: public page provenance + OpenAPI + docs

**Files:** Modify `publicpages/application/PublicVerificationPageService.kt` (+ DTOs) to surface `organizationName`/source on the CORPORATE_DOMAIN_CONFIRMED badge from signal evidence; Test extend `publicpages/PublicVerificationIntegrationTest.kt`; Regenerate `apps/backend/api/openapi.yaml`; Modify `docs/agent/IMPLEMENTATION_HISTORY.md` (iteration-12 entry).

- [ ] **Step 1:** Public page badge display reads the signal evidence it already loads; when `organizationNameSource=verified-record`, include `organizationName` + a `source` field in the badge DTO; else keep current. No new backend lookups on the public path.
- [ ] **Step 2: Tests** — public page for a doc whose corporate signal is verified-record shows the org name + verified-record source; recommender-stated case unchanged.
- [ ] **Step 3:** `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`; full `./gradlew test --rerun` green. IMPLEMENTATION_HISTORY iteration-12 entry (shipped + deferred: org management/verification via admin, domain-ownership proof, contact↔org linking, NAME_MATCH, RU/GLOBAL seeds). Commit — `docs(backend): OpenAPI + docs for organizations; public page org provenance`

---

### Task 5: frontend

**Files:** Regenerate `apps/frontend/lib/api/schema.d.ts` (`npm run gen:api`); Modify the verify-page CORPORATE_DOMAIN_CONFIRMED badge component to show the verified org name + provenance; optional builder hint in `app/(app)/requests/new/page.tsx` via `GET /organizations/lookup`; messages en+ru; Tests: verify-badge org-name rendering, builder hint (mock lookup).

- [ ] **Step 1:** `npm run gen:api`; commit schema.
- [ ] **Step 2:** Verify page: on the corporate-domain badge, when the backend provides an org name with verified-record source, render "at {name} — verified organization record"; when recommender-stated, keep the existing wording. New i18n keys.
- [ ] **Step 3 (optional, low-risk):** Builder recommender step — on contact selection, `GET /organizations/lookup?domain={emailDomain}`; if 200, show "Recognised organization: {name}" hint; 404 → nothing. Debounced/enabled only with a domain.
- [ ] **Step 4:** RTL tests; `npm run lint && npm run check:api && npm run test -- --run && npm run build` green. Commit — `feat(frontend): verified organization provenance on public page and builder hint`

---

### Task 6: E2E + PR

- [ ] **Step 1:** Extend `e2e/canonical-flow.spec.ts` (or a small new spec): drive the canonical flow using a recommender email on a SEEDED verified-org domain, then assert the public verify page shows the verified organization name on the corporate-domain badge. (Pick a seed domain the flow can use for the recommender email — note Mailpit captures any domain locally.)
- [ ] **Step 2:** Local: backend `./gradlew test --rerun`, frontend suite, `npx playwright test` (compose MinIO creds per LOCAL_DEVELOPMENT.md).
- [ ] **Step 3:** Push `feature/organizations`, open PR, babysit checks + bot review loop (remember gen:api if any OpenAPI change lands during fixes).

## Self-review notes

- Spec → tasks: schema+seed→T1, lookup+read API→T2, signal strengthening→T3, public page+OpenAPI→T4, frontend→T5, E2E→T6. Deferred items (management, ownership proof, contact linking, NAME_MATCH, RU seeds) carry no tasks by design.
- Interface consistency: `OrganizationLookup.findVerifiedByDomain` (T2) consumed by T3; `OrganizationView` (T2) returned by the read API and consumed by T5's builder hint; signal evidence keys (`organizationName`, `organizationNameSource`) written in T3, read in T4/T5.
- Module boundary: organizations must not import requests/verification; the dependency is requests→organizations (verify with ModularityTests in T2/T3).
