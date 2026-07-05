# Recipient Review & Document Generation — Design

Date: 2026-07-05
Status: approved
Scope: MVP roadmap items "Documents" (recipient review, version locking) + minimal
"Files" slice + "Core verification signals" creation — `USER_FLOWS.md` Flow 4.

## Goal

When the recipient accepts a submitted response in `NEEDS_REVIEW`, generate the document
(content → sanitized HTML → PDF), store the PDF in region-local object storage, hash and
lock the version, create the core verification signals, and move the request to
`COMPLETED`. A correction request starts a new response cycle producing the next locked
version. Recipients can list/view their documents and obtain short-lived download links.

Out of scope: user uploads (presigned PUT, antivirus validation pipeline), tombstoning
(privacy module), NAME_MATCH signal (needs a structured recipient-name field in template
answers), share links / public verification page, signal read API and trust summary,
Temporal orchestration (generation is synchronous inside the accept transaction for now).

## Data Model (Flyway V6)

### `file_object` (per FILES_AND_STORAGE.md)

`id`, `bucket`, `storage_key` (opaque: `{region}/{profile_id}/{document_id}/{version_id}/{file_id}`
— IDs only, no PII), `original_filename`, `mime_type`, `size_bytes`, `sha256_hash`
(hash of stored bytes), `purpose` (CHECK on the six catalog values), `status` (CHECK:
PENDING/VALIDATING/READY/REJECTED/DELETED), `uploaded_by_actor_id` (nullable text),
`deleted_at`, `created_at`. Generated PDFs are inserted directly as `READY` — the backend
produced the bytes; the async validation pipeline applies to user uploads only (future).

### `document`

`id`, `owner_profile_id` (FK person_profile), `request_id` (nullable FK reference_request),
`type` (CHECK on the eight document types), `status` (text, `ACTIVE` for MVP),
`current_version_id` (nullable FK document_version, set after version insert),
`created_at`, `updated_at`.

### `document_version`

`id`, `document_id` (FK), `version_number` (int, UNIQUE with document_id),
`content_json` (jsonb, nullable for future tombstoning), `rendered_html` (nullable),
`pdf_file_id` (FK file_object, nullable), `sha256_hash` (hash of CANONICAL content_json —
stable serialization with sorted keys; the PDF bytes hash lives on FileObject),
`status` (CHECK: LOCKED/TOMBSTONED), `locked_at`, `locked_by_actor_id` (text),
`tombstoned_at`, `created_at`.

**Domain rule enforced by construction: versions are inserted already LOCKED and no code
path updates a locked version.** Tombstoning (the only permitted change) ships with the
privacy module.

### `verification_signal` (per VERIFICATION_SIGNALS.md)

`id`, `entity_type`, `entity_id`, `signal_type`, `status` (CHECK on the six statuses),
`evidence_json` (jsonb), `provider`, `verified_at`, `expires_at`, `created_at`.
Index `(entity_type, entity_id)`.

## Modules

### `files` (minimal slice)

- `infrastructure/StoragePort` + `S3StorageAdapter` (AWS SDK v2 `S3Client` +
  `S3Presigner`, path-style access for MinIO). The ONLY code touching S3.
- Public API at package root:

```kotlin
data class StoredFile(val fileId: UUID, val sha256: String, val sizeBytes: Long)
data class DownloadLink(val url: String, val expiresAt: OffsetDateTime)

interface FileStore {
    /** Stores backend-generated PDF bytes; inserts FileObject as READY; audited (FILE_UPLOADED). */
    fun storeGeneratedPdf(
        ownerProfileId: UUID, documentId: UUID, versionId: UUID,
        filename: String, bytes: ByteArray,
    ): StoredFile

    /** Short-lived presigned GET for a READY file. Caller performs domain authorization. */
    fun presignedDownloadUrl(fileId: UUID): DownloadLink
}
```

- Config `verifolio.storage`: `endpoint`, `region-name`, `bucket`, `access-key`,
  `secret-key`, `presigned-ttl` (default 5m), `path-style` (true locally).
- Bucket auto-creation on startup for the local/dev profile only.

### `documents`

- Public API at package root:

```kotlin
data class PublishDocumentCommand(
    val ownerProfileId: UUID, val requestId: UUID, val documentType: String,
    val approvedLetterText: String, val answersJson: String, // raw JSON of answers
    val recommenderName: String, val purpose: String?, val lockedByActorId: String,
)
data class PublishedVersion(
    val documentId: UUID, val versionId: UUID, val versionNumber: Int,
    val contentSha256: String, val pdfFileId: UUID, val pdfSha256: String,
)

interface DocumentPublisher {
    /**
     * Find-or-create the document for the request, render sanitized HTML and PDF from the
     * approved letter text, store the PDF via files, and insert the next version already
     * LOCKED. Audits DOCUMENT_CREATED (first version), DOCUMENT_VERSION_CREATED,
     * DOCUMENT_PDF_GENERATED, DOCUMENT_VERSION_LOCKED.
     */
    fun publishLockedVersion(cmd: PublishDocumentCommand): PublishedVersion
}
```

- `domain/CanonicalJson` (sorted-keys serialization + SHA-256), `domain/HtmlRenderer`
  (escaped, no user HTML pass-through), `application/PdfRenderer` (openhtmltopdf; output
  smoke-checked to start with `%PDF`).
- Recipient API: `GET /api/v1/documents` (owner-scoped keyset list),
  `GET /api/v1/documents/{id}` (detail incl. versions),
  `GET /api/v1/documents/{id}/versions/{versionNumber}/download-url` → `DownloadLink`
  (owner-scoped; audits `FILE_DOWNLOAD_GRANTED`). The generic
  `/api/v1/files/{id}/download-url` endpoint is deferred to the uploads iteration.

### `verification`

- Public write API at package root (single owner of signal records):

```kotlin
interface VerificationSignals {
    /** Creates a VERIFIED signal (verified_at = now); audits VERIFICATION_SIGNAL_CREATED. */
    fun createVerified(
        entityType: String, entityId: UUID, signalType: String,
        evidence: Map<String, String>, provider: String? = null,
    ): UUID
}
```

- Read API / trust summary deferred to the public verification page iteration.

### `requests` (orchestrator)

`POST /api/v1/reference-requests/{id}/accept` (owner; CAS from NEEDS_REVIEW):
1. Load the submitted response (`submitted_at IS NOT NULL`, latest).
2. `documents.publishLockedVersion(...)` — document type mapped from the template type.
3. Create signals via `verification`:
   - entity `REFERENCE_RESPONSE`: `RECIPIENT_CONFIRMED`, `RECOMMENDER_RELATIONSHIP_CONFIRMED`
     (evidence includes `statedByRecommender: true`, relationship from the contact),
     `EMAIL_CONFIRMED` (evidence: email domain + confirmation semantics),
     `CORPORATE_DOMAIN_CONFIRMED` — only when the recommender email domain is NOT on the
     configured deny-list of free/disposable providers (`verifolio.verification.free-email-domains`);
   - entity `DOCUMENT_VERSION`: `VERSION_LOCKED`, `DOCUMENT_HASH_LOCKED`.
4. Transition `NEEDS_REVIEW → COMPLETED`; audit `REFERENCE_RESPONSE_ACCEPTED`.
5. Response DTO includes `documentId`.

All synchronous in one transaction (documented trade-off; Temporal later). PDF generation
failure rolls the whole acceptance back.

`POST /api/v1/reference-requests/{id}/request-correction` (owner; CAS from NEEDS_REVIEW):
- body: optional `message` (≤2000 chars) — included in the email only, not persisted;
- status → `CORRECTION_REQUESTED`; audit `REQUEST_CORRECTION_REQUESTED`;
- mint a NEW invitation token and email the recommender (the previous token was consumed
  and sessions revoked at submission; return requires fresh email confirmation per
  AUTHENTICATION.md). Send rate limit does not apply (recipient-initiated correction, not
  a new request); the code-issue limiter still applies on return.

Recommender return path (changes in `RecommenderFlowService`):
- `open` serves the preview for `CORRECTION_REQUESTED` without a status change;
- consent gate is NOT repeated (consent already GRANTED; gate transitions only from OPENED);
- first `saveDraft` in `CORRECTION_REQUESTED` transitions to `IN_PROGRESS` and audits
  `REFERENCE_RESPONSE_STARTED` (per the WORKFLOWS.md transition table); submission then
  follows the normal path and the eventual acceptance locks the corrected text.

Versioning note: corrections happen BEFORE acceptance (COMPLETED is terminal), so an MVP
request produces exactly one accepted, locked version. The `version_number` machinery in
`documents` supports multiple versions per document for the future DSR `CORRECTION` flow,
which reopens a completed request into a new response cycle.

## Signal Evidence (concrete)

- `RECIPIENT_CONFIRMED`: `{ requestId, responseId, confirmedAt }`
- `RECOMMENDER_RELATIONSHIP_CONFIRMED`: `{ requestId, responseId, relationshipType, statedByRecommender: "true" }`
- `EMAIL_CONFIRMED`: `{ emailDomain, requestId, responseId }`
- `CORPORATE_DOMAIN_CONFIRMED`: `{ emailDomain, organizationNameSource: "recommender-stated" }`
- `VERSION_LOCKED`: `{ documentId, versionNumber }`
- `DOCUMENT_HASH_LOCKED`: `{ documentId, versionNumber, contentSha256, pdfSha256 }`

No emails, names, or letter content in evidence.

## Dependencies

- `software.amazon.awssdk:s3` (+ url-connection client)
- `com.openhtmltopdf:openhtmltopdf-pdfbox` (LGPL; server-side use)
- `org.testcontainers:minio` (tests)

## Configuration

```yaml
verifolio:
  storage:
    endpoint: http://localhost:9000
    region-name: local
    bucket: verifolio-local
    access-key: minioadmin      # local dev only
    secret-key: minioadmin      # local dev only
    presigned-ttl: 5m
    path-style: true
  verification:
    free-email-domains: [gmail.com, googlemail.com, yahoo.com, hotmail.com, outlook.com,
                         mail.ru, yandex.ru, icloud.com, proton.me, protonmail.com]
```

## Errors

Existing codes; `INVALID_REQUEST_STATE` (409) for accept/correction from wrong status,
`NOT_FOUND` for foreign/absent documents and files.

## Testing

- Unit: canonical JSON (key order independence, hash stability), HTML escaping
  (`<script>` neutralized), deny-list matcher, PDF renderer smoke (`%PDF` prefix).
- Integration (Testcontainers postgres + MinIO in shared `IntegrationTest`):
  - accept happy path: request COMPLETED; document + version 1 LOCKED; FileObject READY;
    PDF bytes actually present in MinIO and their SHA-256 equals `FileObject.sha256_hash`;
    six signals VERIFIED (five when the recommender domain is free-mail); audits present;
  - gmail recommender → no `CORPORATE_DOMAIN_CONFIRMED`;
  - accept from wrong status → 409; foreign request → 404; accept twice → 409;
  - full correction cycle: request-correction (email with new token) → re-confirm →
    draft flips CORRECTION_REQUESTED→IN_PROGRESS → submit → accept → version 2, version 1
    intact and LOCKED, `current_version_id` points at version 2;
  - documents list/detail owner-scoped; download-url returns a working presigned link
    (bytes fetched over HTTP equal the stored PDF); foreign document → 404;
  - `FILE_DOWNLOAD_GRANTED` audited.
- OpenAPI snapshot refreshed.

## Documentation Updates

- `DATA_MODEL.md` implementation status (V6 tables).
- `ROADMAP.md`: Documents (review/locking) + core verification signals delivered;
  files delivered as the generated-PDF slice.
- `IMPLEMENTATION_HISTORY.md`: iteration 5 entry with deferred items.

## Risks / Accepted Trade-offs

- PDF generation is synchronous inside the accept transaction (seconds); Temporal
  orchestration arrives with "minimal workflows". Failure rolls back acceptance cleanly.
- openhtmltopdf is LGPL — acceptable for server-side use; direct PDFBox layout remains a
  fallback if the dependency becomes a problem.
- The generic `/api/v1/files/{id}/download-url` endpoint and the upload/validation
  pipeline are deferred to the scan-uploads iteration.
- `EMAIL_CONFIRMED` evidence references the confirmation performed during the recommender
  flow (RECOMMENDER_EMAIL_CONFIRMED audit trail) rather than re-verifying at accept time.
