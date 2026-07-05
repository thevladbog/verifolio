# Share Links & Public Verification Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Version-pinned tokenized share links with revoke/expiry, and the public verification page (badges, category trust summary, version info, timeline, downloads) with mandatory audit.

**Architecture:** `documents` owns `share_link` + lifecycle + `ShareLinkAccess`; `verification` owns the display model (public controller, badge catalog, trust summary) and gains `listVerified`/`markRevoked`; `requests` exposes `RequestPublicView`. Spec: `docs/superpowers/specs/2026-07-05-share-links-design.md`.

**Tech Stack:** existing stack; TokenHasher/TokenGenerator promoted to `platform` (documents needs hashing; they are pure technical utils like SlidingWindowRateLimiter).

## Global Constraints

- Raw link token only in the create response (`${frontendBaseUrl}/verify/{raw}`); DB stores HMAC.
- Public endpoints: 404 for ANY invalid token (unknown/revoked/expired/tombstoned) — no state oracle; permitAll + CSRF-exempt `/api/v1/verification-pages/**`; per-IP limiter 300/15min.
- Trust summary: VERIFIED-only counts per category (identity/relationship/documentIntegrity/signature/publication); never a single number.
- Mandatory audit: SHARE_LINK_CREATED/REVOKED, PUBLIC_VERIFICATION_PAGE_VIEWED (sampled, rate from `verifolio.public-page.view-audit-sample-rate`), PUBLIC_VERIFICATION_PAGE_DOWNLOAD + FILE_DOWNLOAD_GRANTED (always), VERIFICATION_SIGNAL_CREATED/UPDATED.
- OpenAPI snapshot refreshed at the end.

---

### Task 1: Promote TokenHasher/TokenGenerator to platform

**Files:**
- Move: `identity/domain/TokenHasher.kt` → `platform/TokenHasher.kt`; `identity/domain/TokenGenerator.kt` → `platform/TokenGenerator.kt` (package `com.verifolio.platform`; git mv + package/import updates)
- Modify: identity usages (`IdentityBeans`, `MagicLinkService`, `SessionService`, `InvitationAccessImpl`, `InvitationTokenServiceImpl`, `RecommenderSessionsImpl`, `AuthController`)

- [ ] **Step 1:** git mv + sed package/imports; `./gradlew test --tests "*Modularity*" --tests "*Session*" --tests "*MagicLink*"` → PASS.
- [ ] **Step 2:** Commit — `refactor(backend): promote TokenHasher and TokenGenerator to platform`

---

### Task 2: V7 migration + config

**Files:**
- Create: `apps/backend/src/main/resources/db/migration/V7__share_links.sql`
- Modify: `platform/VerifolioProperties.kt`, `application.yaml`

- [ ] **Step 1: Migration**

```sql
create table share_link (
    id                  uuid primary key default gen_random_uuid(),
    document_id         uuid not null references document (id),
    document_version_id uuid not null references document_version (id),
    token_hash          text not null unique,
    visibility          text not null default 'PUBLIC' check (visibility in ('PUBLIC')),
    expires_at          timestamptz,
    revoked_at          timestamptz,
    created_at          timestamptz not null default now()
);
create index idx_share_link_document on share_link (document_id);
```

- [ ] **Step 2: Config** — `VerifolioProperties` gains `publicPage: PublicPage` with `viewAuditSampleRate: Double = 1.0`; yaml adds `verifolio.public-page.view-audit-sample-rate: 1.0` with a production-tuning comment.
- [ ] **Step 3:** `./gradlew generateJooq compileKotlin` → BUILD SUCCESSFUL. Commit — `feat(backend): V7 share_link migration and public-page config`

---

### Task 3: verification — signal read/revoke API

**Files:**
- Modify: `verification/VerificationSignals.kt` (+ `SignalView(signalType, status, verifiedAt)` data class; `listVerified(entityType, entityId): List<SignalView>`; `markRevoked(entityType, entityId, signalType): Int`)
- Modify: `verification/application/VerificationSignalsImpl.kt` (listVerified: status=VERIFIED filter; markRevoked: UPDATE status=REVOKED where VERIFIED, audit `VERIFICATION_SIGNAL_UPDATED` metadata {signalType, entityType, entityId, newStatus: REVOKED} per row)

- [ ] **Step 1: Implement; compile.** Commit — `feat(backend): verification signal read and revoke API`

---

### Task 4: documents — share_link lifecycle + ShareLinkAccess

**Files:**
- Create: `documents/ShareLinkAccess.kt` (public API: `SharedVersionView`, `ShareLinkAccess` per spec — reuses `files.DownloadLink`)
- Create: `documents/application/ShareLinkService.kt` (owner ops + resolve/presign; uses platform TokenGenerator/TokenHasher bean, `VerificationSignals`, `FileStore`, `AuditService`)
- Create: `documents/api/ShareLinkController.kt` + DTOs in `documents/api/ShareLinkDtos.kt` (`CreateShareLinkRequest(expiresInDays: Int? @Min(1) @Max(365))`, `ShareLinkCreatedResponse(id, url, versionNumber, expiresAt, createdAt)`, `ShareLinkResponse(id, versionNumber, expiresAt, revokedAt, createdAt)`, `ShareLinkListResponse(items)`)
- Test: extend integration in Task 6

Behaviour:
- create: owner-scoped document; `current_version_id` required (409); token = TokenGenerator.generate(); insert; `verificationSignals.createVerified("SHARE_LINK", linkId, "PUBLIC_VERIFICATION_ENABLED", {documentId, versionNumber})`; audit `SHARE_LINK_CREATED` {documentId, versionNumber, expiresAt?}; url `${props.auth.frontendBaseUrl}/verify/$raw`.
- list: by document (owner-scoped), ordered created_at desc.
- revoke (`POST /api/v1/share-links/{id}/revoke`): owner via document join; `revoked_at is null` guard → 409; set revoked_at; `markRevoked("SHARE_LINK", id, "PUBLIC_VERIFICATION_ENABLED")`; audit `SHARE_LINK_REVOKED`.
- resolve(raw): hash lookup; null when revoked/expired(`expires_at <= now`)/version TOMBSTONED; returns `SharedVersionView` incl. `supersededByNewerVersion` (pinned number < current version number via current_version_id join).
- presignPinnedPdf(raw): resolve else NOT_FOUND ApiException; version.pdf_file_id → `fileStore.presignedDownloadUrl`.

- [ ] **Step 1: Implement all; compile.** Commit — `feat(backend): share link lifecycle and ShareLinkAccess`

---

### Task 5: requests — RequestPublicView

**Files:**
- Create: `requests/RequestPublicView.kt` (public API per spec: `RequestPublicInfo(recommenderName, relationshipType, purpose, requestCreatedAt, responseSubmittedAt)`)
- Create: `requests/application/RequestPublicViewImpl.kt` (request row + latest submitted response)

- [ ] **Step 1: Implement; compile.** Commit — `feat(backend): request public read model for the verification page`

---

### Task 6: verification — public page composition + endpoints + tests

**Files:**
- Create: `verification/api/PublicVerificationController.kt` (`GET /api/v1/verification-pages/{token}`, `GET /{token}/download-url`; injects HttpServletRequest for ip/ua hashing via platform TokenHasher bean)
- Create: `verification/api/PublicVerificationDtos.kt` (`VerificationPageResponse(header, recipient, recommender?, badges, trustSummary, version, timeline, disclaimer, privacyNotice)` with nested DTOs; `BadgeDto(signalType, title, status, date, limitation?)`, `TimelineEntryDto(event, at)`)
- Create: `verification/application/PublicVerificationPageService.kt` (composition per spec; view-audit sampling `Random.nextDouble() < props.publicPage.viewAuditSampleRate`, actor PUBLIC_VIEWER)
- Create: `verification/domain/BadgeCatalog.kt` (signalType → title + limitation texts from VERIFICATION_SIGNALS.md; unknown types fall back to the raw type name) and `verification/domain/TrustSummary.kt` (category mapping function)
- Modify: `identity/api/SecurityConfig.kt` (permitAll + CSRF ignore `/api/v1/verification-pages/**`), `requests/infrastructure/RequestsBeans.kt` or new `verification` beans file for `verificationPageIpLimiter` (300/15min) — place the bean in a new `verification/infrastructure/VerificationBeans.kt`
- Test: `verification/domain/TrustSummaryTest.kt`, `BadgeCatalogTest.kt` (unit); extend `RecommenderFlowIntegrationTest` or new `PublicVerificationIntegrationTest.kt` (integration; reuse requester/recommender helpers by promoting them? — copy the minimal helpers into the new test class, same established pattern)

Integration test list: create link returns raw URL once + signal + audit; page 200 with badges (7 incl. publication), trustSummary counts {identity:2, relationship:2, documentIntegrity:2, publication:1}, version info, timeline ≥4 entries, disclaimer; revoked → 404 + signal REVOKED; expired (UPDATE expires_at past, scoped) → 404; unknown token 404; foreign owner cannot create/revoke (404); double revoke 409; download-url bytes hash matches + both audits; view rate 1.0 → PUBLIC_VERIFICATION_PAGE_VIEWED per view (scoped count by entity_id).

- [ ] **Step 1: Unit tests (fail) → implement domain → pass.**
- [ ] **Step 2: Service + controller + security config.**
- [ ] **Step 3: Integration tests → run → PASS.** Commit — `feat(backend): public verification page endpoints with badges, trust summary, audit`

---

### Task 7: OpenAPI + docs + full suite

- [ ] `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`; DATA_MODEL status (V7), ROADMAP (share links + public page delivered; certificate PDF and scan/signature sections pending), IMPLEMENTATION_HISTORY iteration 6; `./gradlew test --rerun-tasks -x generateJooq` → all green + counts. Commit — `docs(backend): OpenAPI + docs for share links`

---

### Task 8: Push and PR

- [ ] `git push -u origin feature/share-links`; `gh pr create` with summary, spec/plan links, AGENTS.md checklist, unresolved risks (lazy signal expiry, in-process sampling, recipient signals minimal).
