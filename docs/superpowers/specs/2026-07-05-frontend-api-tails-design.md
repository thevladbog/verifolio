# Frontend API Tails — Design

Date: 2026-07-05
Status: approved (user directive: proceed through the queue in order)
Scope: the three backend API gaps discovered while building the frontend MVP (PR #18),
plus their frontend consumption. Iteration 10.

## Gaps addressed

### 1. Owner reads the submitted response before accepting (Flow 4)

Today the recipient decides accept/request-correction blind: no endpoint exposes the
submitted response content; `accept` reads it internally
(`ReferenceRequestService.accept`).

**New endpoint** — `GET /api/v1/reference-requests/{id}/response` (owner-scoped, same
authorization pattern as the other request endpoints: profile lookup + ownership WHERE;
404 on foreign/missing):

```json
{
  "approvedLetterText": "...",
  "answers": { },
  "submittedAt": "...",
  "recipientConfirmed": true,
  "relationshipConfirmed": true,
  "uploads": [
    {"id": "...", "kind": "SCAN", "contentType": "application/pdf",
     "sizeBytes": 123456, "sharedPublicly": false, "targetUploadId": null}
  ]
}
```

- Source: latest `submitted_at IS NOT NULL` row of `reference_response` (same query as
  accept) + READY `response_upload` rows joined to `file_object` for metadata.
- 404 `NOT_FOUND` when no submitted response exists (covers pre-SUBMITTED states) — no
  separate error code; the owner UI only calls it in NEEDS_REVIEW.
- Available in any status once a submitted response exists (COMPLETED too — harmless,
  content is owner-visible via the document anyway).
- **No pre-accept file downloads**: upload metadata only (kind, contentType, sizeBytes,
  sharedPublicly). Presigned owner downloads before acceptance would need new files
  authorization — consciously deferred; the generated document after accept remains the
  reading path for file content.
- Read is audited? No — same reasoning as template reads (no new authorization boundary:
  owner reads their own request's response, which `accept` already implies). The
  sensitive action remains accept/correction, both audited.

### 2. Consent texts served by the backend

Today `consent_record.policy_text_version` stores `textId:version` from
`VerifolioProperties.consents`, but the actual copy lives in frontend i18n keyed by
textId — the backend cannot prove what text was shown, and RU/EU cells would need
frontend redeploys for text changes (REGION_POLICIES.md wants per-region texts in
config).

**New module surface (templates module? no — platform-adjacent):** a small read-only
`consents` slice inside the `requests` module is wrong too; texts are region policy.
Decision: **new package `policies`** is overkill for MVP — serve from the `platform`
module's web layer? Modulith discipline says: **`templates` module hosts it** — it is
already the read-only catalog module (template texts ≈ policy texts, same trust tier,
no auth boundary). Endpoint lives in templates module as `ConsentTextController`.

- **Storage**: classpath resources `consent-texts/{textId}/{version}/{locale}.md`
  (markdown body; first `# ...` line is the display title). Ships with the deployment —
  per-cell config selects WHICH textId:version is active (already the case); resources
  carry the copy for every version still referenced by stored consent records.
- **Endpoint** — `GET /api/v1/consent-texts/{consentType}?locale=` with `consentType ∈
  {REQUESTER_VERBAL_CONSENT_ATTESTATION, RECOMMENDER_PROCESSING_CONSENT,
  CROSS_BORDER_TRANSFER_CONSENT, RECOMMENDER_PUBLIC_SHARING_CONSENT}`:

```json
{"consentType": "RECOMMENDER_PROCESSING_CONSENT", "textId": "local-processing",
 "version": 1, "locale": "en", "title": "...", "body": "..."}
```

  Resolution: consentType → active `ConsentText` from `VerifolioProperties.consents` →
  resource at requested locale, falling back `ru→en`. 404 for unknown type; 500 (config
  error, logged) if the active version's resource is missing — a startup check validates
  all four active texts have at least an `en` resource.
- permitAll (like templates): texts are public policy documents; no audit on reads.
- Seed copy: `local-*` texts get real (plain, non-legal placeholder-quality) en+ru
  bodies moved from the frontend i18n files.
- The frontend consent gate and attestation checkbox render the backend body and stop
  hardcoding copy (spec non-negotiable "no consent copy hardcoded" finally holds).

### 3. Decline with a reason category (design screen 9d)

Today `POST /invitations/{token}/decline` takes no body; reason is a hardcoded audit
metadata string (`declined` / `abuse_report` / `consent_declined`).

- **Request body (optional)**: `{"reasonCategory": "TOO_BUSY"}` with enum
  `DONT_KNOW_REQUESTER | TOO_BUSY | NOT_COMFORTABLE | OTHER`. No free-text field —
  free text from an unauthenticated one-click link is a PII/abuse/erasure liability;
  the enum covers the design's dropdown. Absent body ⇒ behaves exactly as today.
- **Storage**: Flyway **V10** adds `reference_request.declined_reason text` (CHECK on
  the four values, nullable). Set only by the decline transition; readable by the owner
  (request detail shows "Recommender declined: too busy"). Enum categories are not PII —
  they survive recommender PII erasure.
- **Audit**: existing `REQUEST_DECLINED` metadata gains `reasonCategory` when provided
  (enum value — satisfies the IDs/enums-only metadata rule).
- Consent-decline path (`consent { decision: DECLINED }`) also accepts an optional
  `reasonCategory` and writes the same column.
- The recommender-facing decline page gets an optional reason select (skippable —
  declining must stay one click; the reason is a second optional step after the
  confirmation, not a gate).

### Deferred (unchanged decisions)

- **Dashboard aggregate endpoint** — frontend composition from list endpoints causes no
  measured pain; revisit when dashboards grow (trust overview needs signals read API
  anyway).
- **Pre-accept upload downloads for the owner** — see gap 1.

## API/OpenAPI/docs impact

- OpenAPI snapshot refreshed (three path changes); frontend client regenerated in the
  same PR (`check:api` gate).
- `API_GUIDELINES.md`: add the three endpoints; `DATA_MODEL.md`: `declined_reason`
  column; `AUDIT_EVENTS.md`: `REQUEST_DECLINED.reasonCategory` metadata key;
  `REGION_POLICIES.md`: note that consent text copy ships as classpath resources keyed
  by textId/version/locale.
- No new error codes.

## Frontend consumption (same PR)

- `/respond` consent gate + upload sharedPublicly toggle + `/requests/new` attestation
  step fetch `GET /consent-texts/...` and render title+body (markdown → paragraphs);
  hardcoded consent copy removed from `messages/*.json`.
- `/requests/[id]` NEEDS_REVIEW panel switches from placeholder data to
  `GET /reference-requests/{id}/response` (letter serif preview, answers list, uploads
  metadata with kind badges and shared-publicly markers).
- `/invitations/[token]/decline` adds the optional reason select posting
  `{reasonCategory}`; owner request detail renders `declinedReason` when present.

## Non-negotiables check

- New endpoint is owner-scoped with the established authorization pattern — no bypass.
- No object-storage URLs introduced (upload metadata only).
- DB change ships as Flyway V10; V1–V9 untouched.
- API change ships with OpenAPI update; sensitive action set unchanged (decline was
  already audited; reads follow the templates-module no-audit precedent).
- Consent semantics unchanged: what is recorded (`policy_text_version`) now provably
  matches served copy; no consent flow step removed.
- Regional data: consent texts are static policy documents (no personal data); no
  cross-region flow.
