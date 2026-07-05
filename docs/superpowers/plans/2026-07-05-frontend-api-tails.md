# Frontend API Tails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three API gaps from the frontend MVP: owner reads the submitted response before accept, backend-served consent texts, decline with a reason category — plus frontend consumption.

**Architecture:** Backend-first (requests + templates modules, Flyway V10), then OpenAPI snapshot + regenerated frontend client, then the three frontend touchpoints. Spec: `docs/superpowers/specs/2026-07-05-frontend-api-tails-design.md`.

**Tech Stack:** existing backend (Kotlin/Spring/jOOQ) and frontend (Next.js/openapi-fetch) stacks; no new dependencies.

## Global Constraints

- Migrations V1–V9 immutable; new schema = `V10__declined_reason.sql` only.
- Owner endpoint uses the established pattern: `profileService.requireProfileId` + ownership in WHERE + 404 on foreign (`NOT_FOUND`).
- Audit metadata: enum/ID values only (`reasonCategory` enum OK; never free text).
- OpenAPI snapshot refreshed via `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`; frontend `npm run gen:api` in the same PR (`check:api` gate).
- Reads of response/consent-texts are NOT audited (templates-module precedent; no new authz boundary).
- All new user-facing frontend strings via next-intl en+ru.
- Integration tests extend `testsupport.IntegrationTest`; no sleeps.

---

### Task 1: V10 + decline reason (backend)

**Files:** Create `apps/backend/src/main/resources/db/migration/V10__declined_reason.sql`; Modify `requests/api/InvitationController.kt` (decline body), `requests/application/RecommenderFlowService.kt` (declineByToken signature + consent decline), `requests/api/ReferenceRequestController.kt` DTO (expose `declinedReason` in owner get/list), `docs/DATA_MODEL.md`, `docs/AUDIT_EVENTS.md`; Test `requests/RecommenderFlowIntegrationTest.kt` (or the existing decline test class).

**Interfaces (produces):** `DeclineReason` enum `DONT_KNOW_REQUESTER|TOO_BUSY|NOT_COMFORTABLE|OTHER` at `requests.domain`; `declineByToken(rawToken, reason: String, reasonCategory: DeclineReason?)`.

- [ ] **Step 1: Migration**

```sql
alter table reference_request add column declined_reason text
  check (declined_reason in ('DONT_KNOW_REQUESTER','TOO_BUSY','NOT_COMFORTABLE','OTHER'));
```

- [ ] **Step 2:** `data class DeclineRequest(val reasonCategory: DeclineReason? = null)` — controller: `fun decline(@PathVariable token: String, @RequestBody(required = false) body: DeclineRequest?)`; pass `body?.reasonCategory` through. `report-abuse` stays category-less. Consent endpoint's `ConsentDecisionRequest` gains `val reasonCategory: DeclineReason? = null`, applied only when `decision == DECLINED`.
- [ ] **Step 3:** `declineByToken` sets `DECLINED_REASON` in the same UPDATE as the status transition; audit metadata adds `"reasonCategory" to it.name` when non-null. Owner request DTO adds `declinedReason: String?`.
- [ ] **Step 4: Integration tests** — decline with body → 200, column set, audit metadata contains reasonCategory, owner GET returns it; decline without body → 200, column null (backwards compatible); invalid category string → 400; consent-decline with category → column set.
- [ ] **Step 5:** `./gradlew generateJooq test` green on touched tests; commit — `feat(backend): decline reason category (V10)`

---

### Task 2: owner response read endpoint (backend)

**Files:** Create `requests/api/ResponseReviewController.kt` (or extend `ReferenceRequestController`), `requests/application/ResponseReviewService.kt`; Test new `requests/ResponseReviewIntegrationTest.kt`; Modify `docs/API_GUIDELINES.md`.

**Interfaces (produces):** `GET /api/v1/reference-requests/{id}/response` → `SubmittedResponseView(approvedLetterText, answers: Map<String,Any?>, submittedAt, recipientConfirmed, relationshipConfirmed, uploads: List<UploadMeta(id, kind, contentType, sizeBytes, sharedPublicly, targetUploadId)>)`.

- [ ] **Step 1:** Service: ownership-checked request load (no forUpdate — read only), latest submitted response (same query as `accept`), uploads = READY `response_upload` join `file_object` (contentType, sizeBytes from file_object). 404 `NOT_FOUND` if request foreign/missing or no submitted response.
- [ ] **Step 2: Integration tests** — full happy path (drive an existing helper flow to SUBMITTED): owner gets letter+answers+uploads with sharedPublicly flags; other user → 404; before submission → 404; after accept (COMPLETED) → still 200.
- [ ] **Step 3:** Green; commit — `feat(backend): owner reads submitted response before accept`

---

### Task 3: consent texts endpoint (backend)

**Files:** Create `templates/api/ConsentTextController.kt`, `templates/application/ConsentTextService.kt`, resources `apps/backend/src/main/resources/consent-texts/local-{requester-attestation,processing,cross-border,public-sharing}/1/{en,ru}.md`; Modify `platform/VerifolioProperties.kt` (nothing — config already carries textId/version), `docs/REGION_POLICIES.md`, `docs/API_GUIDELINES.md`; Test `templates/ConsentTextIntegrationTest.kt`.

**Interfaces (produces):** `GET /api/v1/consent-texts/{consentType}?locale=` → `ConsentTextView(consentType, textId, version, locale, title, body)`; startup validation bean failing fast if an active text lacks an `en` resource.

- [ ] **Step 1:** Service: consentType→`props.consents.*` map; resource path `consent-texts/{textId}/{version}/{locale}.md`; fallback locale→en; title = first heading (`#`) line, body = remainder. `@PostConstruct` check: all four active texts resolvable in `en`.
- [ ] **Step 2:** Controller permitAll (add path to the security config's public matchers alongside `/api/v1/templates/**`); unknown consentType → 404.
- [ ] **Step 3:** Move the real en+ru copy from `apps/frontend/messages/*.json` consent strings into the four resource pairs.
- [ ] **Step 4: Integration tests** — en + ru + fallback (locale=de → en) + unknown type 404 + anonymous access 200.
- [ ] **Step 5:** Green; commit — `feat(backend): consent texts served from region-configured resources`

---

### Task 4: OpenAPI + docs sync

**Files:** Regenerate `apps/backend/api/openapi.yaml`; Modify `docs/API_GUIDELINES.md` (three endpoints), `docs/agent/IMPLEMENTATION_HISTORY.md` (iteration-10 entry).

- [ ] **Step 1:** `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`; verify the three paths appear.
- [ ] **Step 2:** Full backend suite `./gradlew test` green. Commit — `docs(backend): OpenAPI + docs for frontend API tails`

---

### Task 5: frontend consumption

**Files:** Modify `apps/frontend/lib/api/schema.d.ts` (`npm run gen:api`), `app/(app)/requests/[id]/page.tsx` (review panel ← real response; declinedReason display), `components/respond/consent-gate.tsx` + `components/respond/upload-card.tsx` + `app/(app)/requests/new/page.tsx` (render backend consent texts), `app/(public)/invitations/[token]/decline/page.tsx` (optional reason select), `messages/en.json`/`ru.json` (remove consent copy, add reason labels); Tests: extend `request-detail.test.tsx`, `respond.test.tsx`, add decline-reason test.

- [ ] **Step 1:** `npm run gen:api`; commit the regenerated schema.
- [ ] **Step 2:** `useConsentText(consentType)` query hook (staleTime Infinity); consent gate/attestation/sharing toggle render `title` + `body` paragraphs from the API with a skeleton fallback; delete the now-unused i18n consent strings (keep short checkbox labels — the labels are UI, the policy text is content).
- [ ] **Step 3:** Review panel: `GET /reference-requests/{id}/response` in NEEDS_REVIEW — serif letter preview, answers as term/definition list, uploads metadata rows (kind badge, size, "may be shared publicly" marker). Detail page shows `declinedReason` label for DECLINED requests.
- [ ] **Step 4:** Decline page: after the confirm step, optional select (4 categories + "prefer not to say" = omit) included in the POST body.
- [ ] **Step 5:** RTL tests: consent gate renders fetched body before enabling accept; review panel renders letter/answers/uploads from mocked endpoint; decline posts reasonCategory when chosen and omits body otherwise. `npm run lint && npm run check:api && npm run test -- --run && npm run build` green. Commit — `feat(frontend): consume response review, consent texts, decline reason`

---

### Task 6: E2E + PR

- [ ] **Step 1:** Extend `e2e/canonical-flow.spec.ts`: before accept, the review panel shows the recommender's letter text; extend `one-clicks.spec.ts`: decline with a chosen reason → owner detail shows it.
- [ ] **Step 2:** Local full run: backend `./gradlew test`, frontend suite + `npx playwright test` against the local stack.
- [ ] **Step 3:** Push branch `feature/frontend-api-tails`, open PR; babysit checks + bot review fix loop.

## Self-review notes

- Spec §1 → Task 2; §2 → Task 3; §3 → Task 1; API/docs impact → Task 4; frontend → Task 5; deferred items carry no tasks by design.
- Type consistency: `DeclineReason` (T1) reused by T5's select values; `SubmittedResponseView` (T2) shape mirrored by T5's review panel; consent endpoint path identical in T3 backend and T5 hook.
