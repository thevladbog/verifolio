# Recommender Uploads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recommender evidence uploads (scan/signed PDF/detached signature/attachment) via constrained presigned PUT + synchronous confirm-validation, per-upload public-sharing consent, attachments + SCAN/SIGNATURE signals at acceptance, and consent-gated public downloads.

**Architecture:** files owns the upload mechanics (`FileUploads`), requests owns the recommender endpoints + consent + acceptance attachment/signal creation, documents exposes attachment access on shared versions, publicpages lists/serves downloads. Spec: `docs/superpowers/specs/2026-07-05-recommender-uploads-design.md`.

**Tech Stack:** existing stack; S3Presigner PUT presigning with signed Content-Type/Content-Length.

## Global Constraints

- No S3 calls outside files; presigned PUT constrained (signed content-type + content-length, TTL `verifolio.storage.upload-url-ttl` 10m); opaque keys, no PII.
- Confirm validation: actual size == declared, magic bytes match declared MIME (`%PDF-`, `FF D8 FF`, `89 50 4E 47`, DER `0x30` for pkcs7), SHA-256 stored; REJECTED deletes the object.
- SIGNATURE_ATTACHED evidence must include `targetFileId` (the covered upload, never the generated PDF); SIGNATURE_VERIFIED is never asserted.
- Unconsented uploads: listed publicly as `downloadable: false` WITHOUT filename; download-url → 404.
- Audit: FILE_UPLOAD_REQUESTED/FILE_UPLOADED/FILE_VALIDATED/FILE_DELETED, CONSENT_GRANTED, signal events, public download events.
- OpenAPI snapshot refreshed at the end.

---

### Task 1: V8 migration + config

**Files:** Create `apps/backend/src/main/resources/db/migration/V8__response_uploads.sql`; Modify `platform/VerifolioProperties.kt`, `application.yaml`.

- [ ] **Step 1: Migration**

```sql
create table response_upload (
    id               uuid primary key default gen_random_uuid(),
    request_id       uuid not null references reference_request (id),
    file_object_id   uuid not null unique references file_object (id),
    kind             text not null check (kind in ('SCAN','SIGNED_PDF','DETACHED_SIGNATURE','ATTACHMENT')),
    target_upload_id uuid references response_upload (id),
    shared_publicly  boolean not null default false,
    consent_record_id uuid references consent_record (id),
    created_at       timestamptz not null default now()
);
create index idx_response_upload_request on response_upload (request_id);

create table document_attachment (
    id                  uuid primary key default gen_random_uuid(),
    document_version_id uuid not null references document_version (id),
    file_object_id      uuid not null references file_object (id),
    type                text not null check (type in ('SCAN','SIGNED_PDF','DETACHED_SIGNATURE','ATTACHMENT')),
    created_at          timestamptz not null default now()
);
create index idx_document_attachment_version on document_attachment (document_version_id);
```

- [ ] **Step 2: Config** — `Storage` gains `maxUploadBytes: Long = 15 * 1024 * 1024`, `uploadUrlTtl: Duration = Duration.ofMinutes(10)`; `Consents` gains `publicSharing: ConsentText = ConsentText("local-public-sharing", 1)`; yaml mirrors.
- [ ] **Step 3:** `./gradlew generateJooq compileKotlin` → BUILD SUCCESSFUL. Commit — `feat(backend): V8 response uploads migration and config`

---

### Task 2: files — MimeSniffer + FileUploads

**Files:** Create `files/domain/MimeSniffer.kt` (+ unit test `files/domain/MimeSnifferTest.kt`), `files/FileUploads.kt` (public API per spec), `files/application/FileUploadsImpl.kt`; Modify `files/infrastructure/S3StorageAdapter.kt` (add `presignPut(key, contentType, contentLength, ttl): String`, `headSize(key): Long?`, `getBytes(key): ByteArray`).

- [ ] **Step 1 (TDD): MimeSnifferTest** — `matches(bytes, "application/pdf")` true for `"%PDF-1.7..."`, false for PNG bytes; JPEG `FF D8 FF E0`; PNG `89 50 4E 47 0D 0A 1A 0A`; pkcs7: leading `0x30` for `application/pkcs7-signature`; `application/octet-stream` always true; unknown MIME false. → FAIL → implement object MimeSniffer → PASS.
- [ ] **Step 2: Adapter + FileUploadsImpl** — requestUpload: purpose→MIME allowlist check (400 VALIDATION_ERROR), size ≤ maxUploadBytes (400), insert FILE_OBJECT PENDING (bucket, key `{region}/uploads/{fileId}`, declared values, sha256 placeholder "pending"), presign PUT, audit FILE_UPLOAD_REQUESTED. confirmUpload: row must be PENDING (else 409); headSize == declared else REJECT; getBytes → sniff else REJECT; sha256 → update row READY + real hash (audits FILE_UPLOADED, FILE_VALIDATED); REJECT path: status REJECTED, S3 delete, audit metadata reason. deleteUpload: PENDING/READY only, S3 delete + status DELETED + deleted_at, audit FILE_DELETED.
- [ ] **Step 3:** compile + unit tests PASS. Commit — `feat(backend): presigned upload request/confirm/delete in files module`

---

### Task 3: requests — recommender upload endpoints

**Files:** Create `requests/api/RecommenderUploadDtos.kt`, extend `requests/api/RecommenderFlowController.kt`, `requests/application/RecommenderFlowService.kt`; Test: extend `RecommenderFlowIntegrationTest.kt`.

**DTOs:** `CreateUploadRequest(kind, @NotBlank filename, @NotBlank mimeType, @Positive sizeBytes, sharedPublicly: Boolean = false, targetUploadId: UUID? = null)`, `UploadCreatedResponse(uploadId, fileId, uploadUrl, expiresAt)`, `UploadResponse(uploadId, kind, filename, status, sharedPublicly, targetUploadId)`, `UploadListResponse(items)`, `ConfirmUploadResponse(status, sha256?)`.

- [ ] **Step 1: Service methods** (`ensureResponseCycle` gate reused):
  - `createUpload`: cap 10 per request (409); DETACHED_SIGNATURE → targetUploadId must reference a READY SCAN/SIGNED_PDF upload of the same request (409); `fileUploads.requestUpload(purpose = kind-mapped, ...)`; insert response_upload.
  - `confirmUpload`: row by id + actor.requestId (404); `fileUploads.confirmUpload`; if READY && sharedPublicly → insert RECOMMENDER_PUBLIC_SHARING_CONSENT GRANTED (contact id from request row, `props.consents.publicSharing.versionedId`, region, reference_request_id) + link consent_record_id + audit CONSENT_GRANTED (metadata: consentType, policyTextVersion, region, uploadId).
  - `listUploads`, `deleteUpload` (response cycle only; `fileUploads.deleteUpload` + row delete; signature targets: deleting a target with dependent signature → 409 `INVALID_REQUEST_STATE`).
- [ ] **Step 2: Endpoints** — POST `/api/v1/recommender/uploads` (201), POST `/{id}/confirm`, GET list, DELETE `/{id}` (204). CSRF applies (session-scoped).
- [ ] **Step 3: Integration tests** — presigned PUT actually uploads bytes from the test (Java HttpClient PUT with Content-Type header); confirm → READY, sha256 matches; wrong magic bytes → REJECTED + object absent (download attempt via storage list not needed — assert FileObject status + confirm response); size mismatch → REJECTED; signature without target → 409; consent record created and linked; delete removes row; cap 10 → 409.
- [ ] **Step 4:** run → PASS. Commit — `feat(backend): recommender evidence uploads with per-upload sharing consent`

---

### Task 4: acceptance — attachments + signals

**Files:** Modify `requests/application/ReferenceRequestService.kt` (accept), Test: extend `RecommenderFlowIntegrationTest.kt`.

- [ ] **Step 1:** in `accept` after `publishLockedVersion`: load READY response_uploads; insert document_attachment rows (type = kind, version = published.versionId); signals: SCAN_ATTACHED once if any SCAN/SIGNED_PDF (evidence fileId); per DETACHED_SIGNATURE → SIGNATURE_ATTACHED (evidence signatureFileId, targetFileId = target upload's file_object_id, format "CMS/CAdES (detached)"). Direct table write to DOCUMENT_ATTACHMENT from requests? — NO: documents owns that table. Add `DocumentPublisher.attachFiles(versionId, attachments: List<AttachmentSpec>)` to the documents public API (`AttachmentSpec(fileObjectId, type)`), audited DOCUMENT_VERSION_CREATED? no — new audit not needed (signal events cover); implement in DocumentPublisherImpl (insert rows).
- [ ] **Step 2: Integration test** — full cycle with SCAN + DETACHED_SIGNATURE: after accept, 2 attachment rows, SCAN_ATTACHED + SIGNATURE_ATTACHED signals with correct evidence, trust summary signature count = 2 on the public page (extended in Task 5's test).
- [ ] **Step 3:** run → PASS. Commit — `feat(backend): attach uploads to locked versions with scan/signature signals`

---

### Task 5: public downloads

**Files:** Modify `documents/ShareLinkAccess.kt` (`SharedVersionView.attachments: List<SharedAttachment>`; `data class SharedAttachment(attachmentId, fileId, kind, filename: String?, publiclyDownloadable)`; `presignAttachment(rawToken, attachmentId): PinnedPdf`), `documents/application/ShareLinkService.kt` (join document_attachment + response_upload + consent for downloadable flag; filename null when not downloadable), `publicpages/api/PublicVerificationDtos.kt` (`DownloadDto(id, kind, downloadable)`, page gains `downloads: List<DownloadDto>`), `publicpages/application/PublicVerificationPageService.kt` (downloads list: generated-pdf + attachments; `attachmentDownloadUrl(rawToken, attachmentId, ipHash, uaHash)`), `publicpages/api/PublicVerificationController.kt` (`GET /{token}/attachments/{attachmentId}/download-url`, operationId `publicAttachmentDownloadUrl`).
Test: extend `PublicVerificationIntegrationTest.kt`.

- [ ] **Step 1: Implement.** Downloadable = shared_publicly && consent_record status GRANTED (withdrawn_at null).
- [ ] **Step 2: Integration tests** — consented SCAN: listed downloadable with kind, download-url serves bytes matching hash + audits; unconsented ATTACHMENT: listed `downloadable: false` without filename, download-url → 404; badges include SCAN_ATTACHED/SIGNATURE_ATTACHED; summary signature = 2.
- [ ] **Step 3:** run → PASS. Commit — `feat(backend): consent-gated public downloads of recommender uploads`

---

### Task 6: OpenAPI + docs + full suite

- [ ] `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"`; DATA_MODEL status (V8), ROADMAP (files uploads + recommender uploads delivered), IMPLEMENTATION_HISTORY iteration 7 (+deferred: antivirus/async pipeline, PENDING cleanup, ADR-0007 verification, withdrawal effects); `./gradlew test --rerun-tasks -x generateJooq` → green + counts. Commit — `docs(backend): OpenAPI + docs for recommender uploads`

---

### Task 7: Push and PR

- [ ] `git push -u origin feature/recommender-uploads`; `gh pr create` with summary, spec/plan links, AGENTS.md checklist, unresolved risks.
