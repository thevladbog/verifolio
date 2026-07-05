# Recommender Uploads — Design

Date: 2026-07-05
Status: approved
Scope: the deferred upload part of the recommender experience — scan / signed PDF /
detached signature / attachment via presigned PUT with synchronous validation,
per-upload public-sharing consent, attachments + signals at acceptance, and public
download sections. `FILES_AND_STORAGE.md` upload flow, `RECOMMENDER_EXPERIENCE.md`
Optional Uploads, `PUBLIC_VERIFICATION_PAGE.md` Downloads.

## Goal

During the response (IN_PROGRESS, or CORRECTION_REQUESTED with the usual cycle flip),
the recommender uploads optional evidence files directly to object storage via
constrained presigned PUT URLs; the backend validates synchronously on confirm
(size, magic-byte MIME sniffing, SHA-256) and records the per-upload
"may be shared publicly" decision as a `RECOMMENDER_PUBLIC_SHARING_CONSENT` record.
At acceptance the READY uploads become `document_attachment` rows on the locked version
with `SCAN_ATTACHED` / `SIGNATURE_ATTACHED` signals; the public page gains a downloads
section where recommender uploads are downloadable only with granted consent.

Out of scope: antivirus scanning and the async validation pipeline (Temporal item —
the VALIDATING phase runs inside the confirm request), PENDING-TTL cleanup job
(workflows item), signature verification and the `Signature` table (ADR-0007, v1.1 —
only `SIGNATURE_ATTACHED` is ever asserted, never `SIGNATURE_VERIFIED`), consent
withdrawal/retraction effects (privacy/retraction flows).

## Data Model (Flyway V8)

### `response_upload`

`id` (uuid pk), `request_id` (FK reference_request), `file_object_id` (FK file_object,
UNIQUE), `kind` (CHECK: SCAN | SIGNED_PDF | DETACHED_SIGNATURE | ATTACHMENT),
`target_upload_id` (nullable self-FK — a detached signature points at the upload it
covers), `shared_publicly` (boolean not null default false), `consent_record_id`
(nullable FK consent_record), `created_at`.

### `document_attachment` (per DATA_MODEL.md)

`id`, `document_version_id` (FK), `file_object_id` (FK), `type` (CHECK: the four kinds),
`created_at`. Index on `document_version_id`.

## files module — upload API

New package-root public API:

```kotlin
data class RequestedUpload(val fileId: UUID, val uploadUrl: String, val expiresAt: OffsetDateTime)
data class UploadOutcome(val status: String /* READY | REJECTED */, val sha256: String?, val reason: String?)

interface FileUploads {
    /**
     * Creates a PENDING FileObject and a constrained presigned PUT
     * (content-length-range = declared size, fixed content-type, short TTL, opaque key
     * {region}/uploads/{request scope ids}/{fileId}). Validates declared MIME/size
     * against the per-purpose policy before presigning. Audits FILE_UPLOAD_REQUESTED.
     */
    fun requestUpload(purpose: String, filename: String, declaredMime: String, declaredSizeBytes: Long, actorId: String?): RequestedUpload

    /**
     * Synchronous validation (the VALIDATING phase runs inside this call): object exists,
     * actual size == declared, magic bytes match the declared MIME, SHA-256 computed.
     * READY on success (audits FILE_UPLOADED + FILE_VALIDATED); REJECTED + S3 object
     * deleted on failure (audit metadata carries the reason). Antivirus deferred.
     */
    fun confirmUpload(fileId: UUID): UploadOutcome

    /** Physical delete of a PENDING/READY upload that is not yet attached; status DELETED, audits FILE_DELETED. */
    fun deleteUpload(fileId: UUID)
}
```

Config `verifolio.storage`: `maxUploadBytes` (default 15 MB), `uploadUrlTtl` (10m).
Per-purpose MIME allowlists (in code, files module):
SCAN / SIGNED_PDF / ATTACHMENT → `application/pdf`, `image/jpeg`, `image/png`;
DETACHED_SIGNATURE → `application/pkcs7-signature`, `application/octet-stream`
(sniffed as DER/CMS: leading 0x30, or accepted as opaque for octet-stream with size cap).
Magic bytes: `%PDF-`, JPEG `FF D8 FF`, PNG `89 50 4E 47`.

## Recommender API (session-scoped, requests module)

Same status gate as drafts: IN_PROGRESS, or CORRECTION_REQUESTED (flips the cycle).

- `POST /api/v1/recommender/uploads`
  Body: `{kind, filename, mimeType, sizeBytes, sharedPublicly, targetUploadId?}`.
  DETACHED_SIGNATURE requires `targetUploadId` referencing a READY upload of kind
  SCAN/SIGNED_PDF on the same request (409 `INVALID_REQUEST_STATE` otherwise; the
  signature covers a specific uploaded file, never the generated PDF).
  → 201 `{uploadId, fileId, uploadUrl, expiresAt}` (uploadId = response_upload id).
  Upload count cap: 10 per request (409 over).
- `POST /api/v1/recommender/uploads/{id}/confirm` → 200 `{status, sha256?}`.
  On READY with `sharedPublicly = true`: insert `RECOMMENDER_PUBLIC_SHARING_CONSENT`
  GRANTED (subject RECOMMENDER, contact + request ids, versioned policy text
  `verifolio.consents.public-sharing`, region) and link `consent_record_id`;
  audit `CONSENT_GRANTED`. REJECTED responses include the reason.
- `GET /api/v1/recommender/uploads` → list `{uploadId, kind, filename, status, sharedPublicly, targetUploadId}`.
- `DELETE /api/v1/recommender/uploads/{id}` → 204; allowed while the request is in a
  response cycle (not after submission); physical delete via `FileUploads.deleteUpload`,
  `response_upload` row removed. A deleted shared upload keeps its consent record
  (history) but nothing references it anymore.

New config: `verifolio.consents.public-sharing` (`ConsentText`, default
`local-public-sharing:1`).

## Acceptance (requests orchestration)

After `publishLockedVersion`, for every READY `response_upload` of the request:
- insert `document_attachment(document_version_id = new version, file_object_id, type = kind)`;
- signals (entity DOCUMENT_VERSION):
  - `SCAN_ATTACHED` — once, if any SCAN or SIGNED_PDF attachment exists
    (evidence: `fileId` of the first such upload);
  - `SIGNATURE_ATTACHED` — per detached signature (evidence: `signatureFileId`,
    `targetFileId` = the covered upload's file id, `format`: "CMS/CAdES (detached)").
Non-READY uploads are ignored (never attached). Audit comes from the signal writer.

## Public page (publicpages + documents)

- `SharedVersionView` gains `attachments: List<SharedAttachment>` where
  `SharedAttachment(attachmentId, fileId, kind, filename, publiclyDownloadable)` —
  `publiclyDownloadable` = the backing `response_upload.shared_publicly` with a granted,
  non-withdrawn consent record. The page payload gains
  `downloads: [{id: "generated-pdf" | attachmentId, kind, downloadable}]` — the generated
  PDF is always downloadable; consent-less uploads are listed with `downloadable: false`
  and no filename (existence is already public via the SCAN_ATTACHED badge; the name may
  contain PII).
- `GET /api/v1/verification-pages/{token}/attachments/{attachmentId}/download-url` —
  404 unless the attachment belongs to the pinned version AND is publicly downloadable;
  full audit (`PUBLIC_VERIFICATION_PAGE_DOWNLOAD` + `FILE_DOWNLOAD_GRANTED`, actor
  PUBLIC_VIEWER). `ShareLinkAccess` gains `presignAttachment(rawToken, attachmentId)`.

Badges/trust summary: SCAN_ATTACHED and SIGNATURE_ATTACHED flow through the existing
catalog and the `signature` category automatically.

## Testing

- Unit: magic-byte sniffer (pdf/jpeg/png/CMS + mismatches).
- Integration:
  - request-upload returns a working presigned PUT (real bytes PUT to MinIO from the
    test), confirm → READY with matching SHA-256;
  - declared-vs-actual mismatch (wrong magic bytes; wrong size) → REJECTED and the S3
    object is gone;
  - detached signature without a valid READY target → 409;
  - sharedPublicly=true → consent record linked, CONSENT_GRANTED audited;
  - full cycle upload→confirm→submit→accept: document_attachment rows, SCAN_ATTACHED +
    SIGNATURE_ATTACHED signals with target evidence, badges appear on the public page,
    trust summary `signature` count = 2;
  - public downloads: consented attachment listed downloadable and the URL serves bytes
    matching the FileObject hash; unconsented attachment listed as not downloadable and
    its download-url → 404;
  - delete before submit removes row + object; upload in wrong status → 409;
  - upload cap → 409; foreign recommender session cannot see another request's uploads
    (implicit via actor scoping).
- OpenAPI snapshot refreshed.

## Documentation Updates

- `DATA_MODEL.md` implementation status (V8).
- `ROADMAP.md`: files upload slice + recommender uploads delivered.
- `IMPLEMENTATION_HISTORY.md` iteration 7 with deferred items (antivirus/async pipeline,
  PENDING cleanup job, signature verification per ADR-0007, consent withdrawal effects).

## Risks / Accepted Trade-offs

- Validation is synchronous inside confirm (seconds for 15 MB); the async pipeline with
  antivirus arrives with Temporal. A crashed confirm leaves a PENDING row + object for
  the future cleanup job.
- DETACHED_SIGNATURE accepted as opaque bytes for `application/octet-stream` (DER sniff
  only for pkcs7 MIME) — no verification claims are made either way.
- Consent records for later-deleted uploads are retained as history (consent was granted
  at the time); nothing references the erased file.
