# Share Links & Public Verification Page — Design

Date: 2026-07-05
Status: approved
Scope: MVP roadmap item "Share links and the public verification page" —
`USER_FLOWS.md` Flows 5–6, `PUBLIC_VERIFICATION_PAGE.md`, trust summary per
`VERIFICATION_SIGNALS.md`.

## Goal

Recipients explicitly create tokenized share links pinned to a document version, with
expiry and revocation; third parties open the public verification page (signal badges,
category trust summary, version info, timeline, disclaimer) and download the generated
PDF through share-link authorization. All mandatory audit events are emitted.

Out of scope: verification certificate PDF, scan/signature download sections, NAME_MATCH,
retraction/tombstone display states (features do not exist yet — the page returns 404 for
tombstoned versions), translations, recipient profile trust signals beyond the name.

## Module Boundaries (per MODULES.md)

- **documents** owns `share_link` and its lifecycle + "public verification page core
  data": new public API `ShareLinkAccess`.
- **verification** owns signal records, trust-summary derivation, and badge catalog texts
  (all exposed at the package root as public display API).
- **publicpages** (new module, documented in MODULES.md) owns the public controller and
  page composition. Revision note: the original plan hosted composition inside
  `verification`, but domain modules write signals INTO verification while the page reads
  FROM documents/requests — ModularityTests flagged the resulting dependency cycles, so
  the composition layer sits on top instead. Nothing may depend on `publicpages`.
- **requests** exposes a new read model `RequestPublicView` for recommender context and
  timeline timestamps.

## Data Model (Flyway V7)

### `share_link` (per DATA_MODEL.md)

`id` (uuid pk), `document_id` (FK document), `document_version_id` (FK document_version —
pinned at creation), `token_hash` (text unique, HMAC), `visibility` (text, CHECK
`('PUBLIC')`), `expires_at` (timestamptz nullable — null = no expiry), `revoked_at`
(timestamptz), `created_at`. Index on `document_id`.

## Owner API (documents module)

`POST /api/v1/documents/{id}/share-links` → 201
Body `{expiresInDays: Int?}` (1..365, null = no expiry). Owner-scoped; document must have
a `current_version_id` (409 `INVALID_REQUEST_STATE` otherwise) — the link pins exactly
that version. Response: `{id, url, versionNumber, expiresAt, createdAt}` where
`url = ${frontendBaseUrl}/verify/{rawToken}` — the raw token appears exactly once, only
here; the DB stores the HMAC hash. Side effects: `PUBLIC_VERIFICATION_ENABLED` signal
(entity SHARE_LINK, entity_id = link id, evidence: documentId, versionNumber), audit
`SHARE_LINK_CREATED`.

`GET /api/v1/documents/{id}/share-links` → 200
Owner-scoped list without tokens: `{id, versionNumber, expiresAt, revokedAt, createdAt}`.

`POST /api/v1/share-links/{id}/revoke` → 200
Owner resolved through the document join; already-revoked → 409 `INVALID_REQUEST_STATE`.
Marks `revoked_at`, flips the link's `PUBLIC_VERIFICATION_ENABLED` signal to REVOKED
(audit `VERIFICATION_SIGNAL_UPDATED`), audit `SHARE_LINK_REVOKED`. Public access stops
immediately (resolve checks `revoked_at`).

## New Cross-Module Read APIs

```kotlin
// documents (package root)
data class SharedVersionView(
    val shareLinkId: UUID, val documentId: UUID, val documentType: String,
    val ownerProfileId: UUID, val requestId: UUID?,
    val versionId: UUID, val versionNumber: Int, val lockedAt: OffsetDateTime,
    val versionStatus: String, val supersededByNewerVersion: Boolean,
    val shareLinkCreatedAt: OffsetDateTime,
)
interface ShareLinkAccess {
    /** null when the token is unknown, revoked, expired, or the version is tombstoned. */
    fun resolve(rawToken: String): SharedVersionView?
    /** Presigned GET for the pinned version's generated PDF. Throws NOT_FOUND when invalid. */
    fun presignPinnedPdf(rawToken: String): DownloadLink   // reuses files.DownloadLink
}

// requests (package root)
data class RequestPublicInfo(
    val recommenderName: String, val relationshipType: String?, val purpose: String?,
    val requestCreatedAt: OffsetDateTime, val responseSubmittedAt: OffsetDateTime?,
)
interface RequestPublicView {
    fun forRequest(requestId: UUID): RequestPublicInfo?
}

// verification (package root, added to the existing interface file)
data class SignalView(
    val signalType: String, val status: String, val verifiedAt: OffsetDateTime?,
)
interface VerificationSignals {
    // existing createVerified(...)
    fun listVerified(entityType: String, entityId: UUID): List<SignalView>
    /** Flips a VERIFIED signal to REVOKED; audits VERIFICATION_SIGNAL_UPDATED. */
    fun markRevoked(entityType: String, entityId: UUID, signalType: String): Int
}
```

## Public API (verification module)

Both endpoints `permitAll` + CSRF-exempt (`/api/v1/verification-pages/**`), rate-limited
per IP (SlidingWindowRateLimiter, 300/15min, bean `verificationPageIpLimiter`).

### `GET /api/v1/verification-pages/{token}` → 200 | 404

Resolve via `ShareLinkAccess.resolve`; 404 `NOT_FOUND` for unknown/revoked/expired/
tombstoned. Composes:

- `header`: `documentType`, `verificationId` (share-link id), `lastVerifiedAt`
  (max `verified_at` across the displayed signals);
- `recipient`: `{name}` (profiles.displayName of the owner);
- `recommender`: `{name, relationshipType, statedByRecommender: true}` from
  `RequestPublicView` (null-safe: absent for request-less documents);
- `badges[]`: for each displayed signal — `signalType`, plain-language `title`,
  `status`, `date`, `limitation` (nullable) from a static catalog map mirroring
  VERIFICATION_SIGNALS.md texts. Sources: signals of the pinned version
  (DOCUMENT_VERSION), of the submitted response (REFERENCE_RESPONSE via requestId),
  and this link's own PUBLIC_VERIFICATION_ENABLED (SHARE_LINK). Only VERIFIED signals
  are shown as confirmed; REVOKED never appears as confirmed;
- `trustSummary`: counts of VERIFIED signals per category — identity
  (EMAIL_CONFIRMED, CORPORATE_DOMAIN_CONFIRMED), relationship (RECIPIENT_CONFIRMED,
  RECOMMENDER_RELATIONSHIP_CONFIRMED), documentIntegrity (VERSION_LOCKED,
  DOCUMENT_HASH_LOCKED), signature (SCAN_ATTACHED, SIGNATURE_ATTACHED,
  SIGNATURE_VERIFIED), publication (PUBLIC_VERIFICATION_ENABLED). Counts only — never a
  single number or percentage;
- `version`: `{versionNumber, lockedAt, status, supersededByNewerVersion}`;
- `timeline[]`: `{event, at}` — "Request sent" (request created_at), "Response submitted"
  (submitted_at), "Recipient accepted"/"Version locked" (locked_at), "Share link created"
  (link created_at); entries with missing timestamps are omitted;
- `disclaimer`: the fixed text from PUBLIC_VERIFICATION_PAGE.md;
- `privacyNotice`: short fixed text disclosing anonymous view telemetry.

View audit: `PUBLIC_VERIFICATION_PAGE_VIEWED` (actor PUBLIC_VIEWER, entity SHARE_LINK,
hashed ip/ua) sampled by `verifolio.public-page.view-audit-sample-rate` (0.0–1.0,
local default 1.0; metadata includes the rate).

### `GET /api/v1/verification-pages/{token}/download-url` → 200 | 404

`ShareLinkAccess.presignPinnedPdf` → `{url, expiresAt}`. Full (unsampled) audit:
`PUBLIC_VERIFICATION_PAGE_DOWNLOAD` (entity SHARE_LINK) and `FILE_DOWNLOAD_GRANTED`
(entity FILE_OBJECT), both actor PUBLIC_VIEWER with hashed ip/ua.

## Signal Lifecycle Notes

- `PUBLIC_VERIFICATION_ENABLED` is per share link (entity SHARE_LINK): created VERIFIED
  at link creation, REVOKED at link revocation. Natural expiry is reflected lazily: an
  expired link stops resolving (404) immediately; its signal row is not proactively
  updated (documented trade-off — a background sweep arrives with the workflows item).
- `supersededByNewerVersion` = pinned version_number < the document's current version
  number. Always false in MVP flows (one accepted version per request); the logic is
  unit-tested for the future DSR CORRECTION path.

## Configuration

```yaml
verifolio:
  public-page:
    view-audit-sample-rate: 1.0   # production cells tune down; downloads always audited
```

## Security

- Raw link tokens only in the create response and the public URL; DB stores HMAC.
- No object storage URLs: downloads via short-lived presigned GET behind the token check.
- Rate limit on the public endpoints per IP; 404 (not 403/410) for any invalid token —
  no state oracle.
- ip/ua audited as keyed HMAC hashes (existing TokenHasher pepper).

## Testing

- Unit: trust-summary categorization; superseded logic; badge catalog completeness for
  all shipped signal types.
- Integration: create link (raw token once, signal created, audit); page renders all
  sections with correct counts (6 VERIFIED badges post-accept + publication);
  revoked link → immediate 404 and signal REVOKED; expired link (insert past expiry) →
  404; unknown token → 404; owner isolation on create/list/revoke; double revoke → 409;
  download URL returns bytes matching the stored PDF hash + both audit events;
  view sampling at rate 1.0 emits one event per view; document without
  `current_version_id` cannot be shared (409 — unreachable via API flows today,
  guarded anyway).
- OpenAPI snapshot refreshed.

## Documentation Updates

- `DATA_MODEL.md` implementation status (V7).
- `ROADMAP.md` — share links + public page delivered (certificate PDF, scan/signature
  sections pending).
- `IMPLEMENTATION_HISTORY.md` iteration 6 entry.

## Risks / Accepted Trade-offs

- Expired links: access stops exactly at expiry, but the PUBLIC_VERIFICATION_ENABLED
  signal row flips only lazily/never until the background sweep ships (workflows item).
- View sampling is in-process randomness; per-cell aggregation pipelines are post-MVP.
- Recipient section carries the display name only; profile trust signals arrive with the
  profile verification item.
