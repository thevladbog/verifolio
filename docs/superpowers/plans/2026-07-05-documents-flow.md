# Recipient Review & Document Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recipient accept/correction in NEEDS_REVIEW: content→HTML→PDF generation, MinIO storage via a minimal files slice, canonical hashing, version locking, core verification signals, COMPLETED transition, and recipient document APIs.

**Architecture:** `files` owns the S3 abstraction (`FileStore`), `documents` owns versions/locking and rendering (`DocumentPublisher`), `verification` owns signal records (`VerificationSignals`), `requests` orchestrates accept/correction. Spec: `docs/superpowers/specs/2026-07-05-documents-flow-design.md`.

**Tech Stack:** AWS SDK v2 S3 (+presigner), openhtmltopdf, Testcontainers MinIO.

## Global Constraints

- No S3/MinIO calls outside the `files` module; no public storage URLs (presigned GET only, TTL 5m default).
- Versions inserted already LOCKED; no update path to locked versions exists.
- `DocumentVersion.sha256_hash` = canonical (sorted-keys) content_json hash; `FileObject.sha256_hash` = PDF bytes hash.
- Object keys are opaque IDs only: `{region}/{profile_id}/{document_id}/{version_id}/{file_id}`.
- Signals per VERIFICATION_SIGNALS.md; evidence contains no emails/names/letter content.
- Audit events: DOCUMENT_CREATED, DOCUMENT_VERSION_CREATED, DOCUMENT_PDF_GENERATED, DOCUMENT_VERSION_LOCKED, FILE_UPLOADED, FILE_DOWNLOAD_GRANTED, VERIFICATION_SIGNAL_CREATED, REFERENCE_RESPONSE_ACCEPTED, REQUEST_CORRECTION_REQUESTED, REFERENCE_RESPONSE_STARTED.
- OpenAPI snapshot refreshed at the end.

---

### Task 1: Dependencies, V6 migration, config

**Files:**
- Modify: `apps/backend/build.gradle.kts`
- Create: `apps/backend/src/main/resources/db/migration/V6__documents_files_signals.sql`
- Modify: `apps/backend/src/main/kotlin/com/verifolio/platform/VerifolioProperties.kt`
- Modify: `apps/backend/src/main/resources/application.yaml`

- [ ] **Step 1: Dependencies** — add to `dependencies {}`:

```kotlin
implementation(platform("software.amazon.awssdk:bom:2.31.78"))
implementation("software.amazon.awssdk:s3")
implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
testImplementation("org.testcontainers:minio")
```

- [ ] **Step 2: Migration** — four tables per the spec's Data Model section (file_object with purpose/status CHECKs; document with type CHECK + nullable current_version_id FK added via ALTER after document_version exists; document_version with UNIQUE(document_id, version_number) and status CHECK LOCKED/TOMBSTONED; verification_signal with status CHECK and (entity_type, entity_id) index).
- [ ] **Step 3: Config** — `VerifolioProperties` gains `storage: Storage` (endpoint, regionName, bucket, accessKey, secretKey, presignedTtl=5m, pathStyle=true) and `verification: Verification` (freeEmailDomains: List<String> with the ten spec defaults). application.yaml mirrors (minioadmin/minioadmin local).
- [ ] **Step 4:** `./gradlew generateJooq compileKotlin` → BUILD SUCCESSFUL. Commit — `feat(backend): V6 migration, storage/verification config, pdf+s3 deps`

---

### Task 2: files module — S3 adapter + FileStore

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/files/FileStore.kt` (public API: `StoredFile`, `DownloadLink`, `FileStore` — signatures from the spec)
- Create: `apps/backend/src/main/kotlin/com/verifolio/files/infrastructure/S3StorageAdapter.kt` (S3Client + S3Presigner beans from props; `put(key, bytes, contentType)`, `presignGet(key, filename, ttl): URL`; ensures bucket exists when `pathStyle` — local dev)
- Create: `apps/backend/src/main/kotlin/com/verifolio/files/application/FileStoreImpl.kt` (`storeGeneratedPdf`: sha256 of bytes, opaque key `{region}/{profile}/{doc}/{version}/{fileId}`, S3 put, insert FILE_OBJECT purpose=GENERATED_PDF status=READY, audit FILE_UPLOADED metadata {purpose, sizeBytes}; `presignedDownloadUrl`: READY row else 404 NOT_FOUND, presigned GET with Content-Disposition filename)
- Modify: `apps/backend/src/test/kotlin/com/verifolio/testsupport/IntegrationTest.kt` (add shared MinIO container: `MinIOContainer("minio/minio:latest")`, register `verifolio.storage.endpoint/access-key/secret-key` dynamic props)

- [ ] **Step 1: Implement all four files.**
- [ ] **Step 2:** `./gradlew test --tests "*ContactIntegration*"` (any integration test boots the context, proving S3 beans + MinIO wiring) → PASS. Commit — `feat(backend): files module — S3 storage adapter and generated-PDF FileStore`

---

### Task 3: verification module — signal writer

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/verification/VerificationSignals.kt` (public API per spec)
- Create: `apps/backend/src/main/kotlin/com/verifolio/verification/application/VerificationSignalsImpl.kt` (insert VERIFIED + verified_at=now + evidence JSONB; audit VERIFICATION_SIGNAL_CREATED metadata {signalType, entityType, entityId})

- [ ] **Step 1: Implement; compile.** Commit — `feat(backend): verification signal writer public API`

---

### Task 4: documents module — rendering, publishing, recipient API

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/documents/DocumentPublisher.kt` (public API per spec: `PublishDocumentCommand`, `PublishedVersion`, `DocumentPublisher`)
- Create: `apps/backend/src/main/kotlin/com/verifolio/documents/domain/CanonicalJson.kt` (`canonicalize(json: String): String` — recursively sorted keys via Jackson TreeNode; `sha256Hex(s: String): String`)
- Create: `apps/backend/src/main/kotlin/com/verifolio/documents/domain/HtmlRenderer.kt` (`render(letterText, recommenderName, purpose, lockedAtIso): String` — full XHTML doc, all inputs HTML-escaped, letter paragraphs from newline splits)
- Create: `apps/backend/src/main/kotlin/com/verifolio/documents/application/PdfRenderer.kt` (openhtmltopdf `PdfRendererBuilder.withHtmlContent(...).toStream(...)`)
- Create: `apps/backend/src/main/kotlin/com/verifolio/documents/application/DocumentPublisherImpl.kt` (find-or-create document by request_id → next version_number → content_json {letterText, answers, recommenderName, purpose} → canonical hash → HTML → PDF → `fileStore.storeGeneratedPdf` → insert version LOCKED (locked_at=now, locked_by_actor_id) → update current_version_id → audits)
- Create: `apps/backend/src/main/kotlin/com/verifolio/documents/api/DocumentController.kt` + `DocumentDtos.kt` (list keyset by owner, detail with versions, `GET /{id}/versions/{versionNumber}/download-url` → files presigned link + audit FILE_DOWNLOAD_GRANTED metadata {fileId, purpose})
- Test: `apps/backend/src/test/kotlin/com/verifolio/documents/domain/CanonicalJsonTest.kt`, `HtmlRendererTest.kt`, `apps/backend/src/test/kotlin/com/verifolio/documents/application/PdfRendererTest.kt`

- [ ] **Step 1: Unit tests first** — canonical JSON: `{"b":1,"a":{"d":2,"c":3}}` and `{"a":{"c":3,"d":2},"b":1}` produce identical canonical strings and hashes; HTML: letterText `<script>alert(1)</script>` renders escaped (`&lt;script&gt;`), recommenderName escaped; PDF: output bytes start with `%PDF`.
- [ ] **Step 2: Run** → FAIL (classes missing) → implement → PASS.
- [ ] **Step 3: Controller endpoints** (list/detail/download-url; owner scoping identical to ContactService pattern; 404 NOT_FOUND for foreign/missing).
- [ ] **Step 4:** `./gradlew test --tests "*documents*"` → PASS. Commit — `feat(backend): documents module — rendering, locked version publishing, recipient API`

---

### Task 5: requests — accept & request-correction + recommender return path

**Files:**
- Modify: `apps/backend/src/main/kotlin/com/verifolio/requests/application/ReferenceRequestService.kt` (accept, requestCorrection)
- Modify: `apps/backend/src/main/kotlin/com/verifolio/requests/api/ReferenceRequestController.kt` (`POST /{id}/accept`, `POST /{id}/request-correction` body `{message?}`)
- Modify: `apps/backend/src/main/kotlin/com/verifolio/requests/api/ReferenceRequestDtos.kt` (`RequestCorrectionRequest(@Size(max=2000) message: String?)`, accept response = ReferenceRequestResponse + documentId)
- Modify: `apps/backend/src/main/kotlin/com/verifolio/requests/application/RecommenderFlowService.kt` (saveDraft accepts CORRECTION_REQUESTED → CAS to IN_PROGRESS + REFERENCE_RESPONSE_STARTED on that flip; requireStatus adjusted)
- Test: extend `ReferenceRequestIntegrationTest.kt` / `RecommenderFlowIntegrationTest.kt`

**Interfaces consumed:** `DocumentPublisher.publishLockedVersion`, `VerificationSignals.createVerified`, `InvitationTokenService.mint`, `MailPort`, `ContactLookup.findOwned` (relationshipType needs extension: add `relationshipType` to `ContactSnapshot`).

- [ ] **Step 1: accept(user, id)** — CAS NEEDS_REVIEW; latest submitted response required; document type from template type (template snapshot); publish; six signals per spec evidence tables (CORPORATE_DOMAIN_CONFIRMED skipped for deny-listed domains — matcher on `props.verification.freeEmailDomains`, subdomain-safe suffix match); transition to COMPLETED; audit REFERENCE_RESPONSE_ACCEPTED.
- [ ] **Step 2: requestCorrection(user, id, message?)** — CAS NEEDS_REVIEW→CORRECTION_REQUESTED; mint fresh invitation token (TTL to expires_at, extend to +7d if already past — keep simple: TTL = max(remaining, 7 days)); email with link + optional message; audit REQUEST_CORRECTION_REQUESTED.
- [ ] **Step 3: Recommender return** — saveDraft: allowed from IN_PROGRESS or CORRECTION_REQUESTED (flip + audit once).
- [ ] **Step 4: Integration tests** — accept happy path (COMPLETED, LOCKED version 1, MinIO bytes hash == FileObject hash, signals, audits); gmail contact → 5 signals; accept twice → 409; foreign → 404; correction full cycle to version 2 (version 1 intact, current_version_id moved); download-url returns fetchable URL with matching bytes.
- [ ] **Step 5:** `./gradlew test --tests "*ReferenceRequest*" --tests "*RecommenderFlow*" --tests "*documents*"` → PASS. Commit — `feat(backend): recipient accept/correction with document generation and signals`

---

### Task 6: OpenAPI + docs + full suite

- [ ] **Step 1:** `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"` → snapshot refreshed.
- [ ] **Step 2:** DATA_MODEL implementation status; ROADMAP (Documents review/locking + core signals + files PDF-slice delivered); IMPLEMENTATION_HISTORY iteration 5 with deferred items (uploads pipeline, tombstoning, NAME_MATCH, signals read API, Temporal).
- [ ] **Step 3:** `./gradlew test --rerun-tasks -x generateJooq` → all green, count from XML. Commit — `docs(backend): OpenAPI + docs for documents flow`

---

### Task 7: Push and PR

- [ ] `git push -u origin feature/documents-flow`; `gh pr create` with summary, spec/plan links, AGENTS.md checklist, unresolved risks.
