# Files & Object Storage

## Storage Decision

Verifolio stores files in S3-compatible object storage.

Local development uses MinIO.

The backend must access object storage only through an internal abstraction.

## File Types

Stored files include:

- generated PDF;
- uploaded scan;
- detached signature file;
- verification certificate;
- preview image;
- source attachment;
- exported document packet.

## Storage Principles

1. Files are private by default.
2. Public object storage URLs are forbidden.
3. File access must go through backend authorization.
4. Download links must be signed and short-lived.
5. Every stored file must have a hash.
6. File metadata must be stored in PostgreSQL.
7. Files must be region-local.
8. File uploads must be validated and audited.

## FileObject Model

```text
FileObject
- id
- bucket
- storage_key
- original_filename
- mime_type
- size_bytes
- sha256_hash
- purpose
- status
- uploaded_by_actor_id
- created_at
```

## FileObject Status Lifecycle

```text
PENDING → VALIDATING → READY | REJECTED
                          ↓
                       DELETED
```

- **PENDING** — metadata created, pre-signed upload URL issued, upload not yet confirmed;
- **VALIDATING** — upload confirmed, async validation pipeline running;
- **READY** — validation passed; the file may be attached, downloaded, and referenced;
- **REJECTED** — validation failed; the object is inaccessible and scheduled for removal;
- **DELETED** — erased per the deletion model below.

Only `READY` files are available to any other module. `PENDING` and `VALIDATING` files must never be served.

`PENDING` objects have a TTL. A cleanup job removes expired pending metadata and any orphaned storage objects.

## Object Key Strategy

Recommended object key format:

```text
{region}/{profile_id}/{document_id}/{document_version_id}/{file_id}
```

Example:

```text
eu/profile_123/doc_456/version_1/file_789
```

Object keys never contain the user-provided filename; keys use opaque IDs only. The original filename lives in FileObject metadata (`original_filename`) and is returned via the `Content-Disposition` header on download.

Do not include raw personal names or emails in object keys.

## Upload Flow

Recommended flow:

1. Frontend asks backend for upload permission.
2. Backend validates actor and purpose.
3. Backend creates FileObject metadata with status `PENDING`.
4. Backend returns a pre-signed PUT URL.
5. Client uploads to object storage.
6. Backend confirms upload and sets status `VALIDATING`.
7. The async validation pipeline runs (see below).
8. Backend marks FileObject `READY` or `REJECTED`.
9. Backend creates audit event.

Pre-signed PUT URLs must be constrained:

- `content-length-range` matching the declared size and the size limit;
- `content-type` fixed to the declared MIME type;
- short expiry;
- single opaque object key (no client-chosen keys).

## Async Validation Pipeline

Validation is an asynchronous post-upload step, executed as a Temporal workflow (see ADR 0005). It gates availability: a file is not visible to any consumer until it reaches `READY`.

The pipeline runs after upload confirmation and includes:

- MIME sniffing (content must match the declared type);
- size verification;
- antivirus scan;
- SHA-256 hash calculation;
- region policy check.

Any failure sets status `REJECTED` and creates an audit event.

## Download Flow

Recommended flow:

1. Client requests file download.
2. Backend checks domain authorization.
3. Backend checks share link state if public.
4. Backend creates short-lived pre-signed download URL.
5. Backend creates audit event if needed.

## File Validation

Minimum validation (enforced by the async pipeline above):

- size limit;
- allowed MIME types;
- extension check;
- content sniffing;
- antivirus scan;
- hash calculation;
- region policy check.

## File Immutability

A FileObject is immutable after it reaches `READY`.

If a file changes, it must be stored as a new FileObject.

## Signatures and File Targets

Digital signatures reference specific FileObjects by SHA-256 hash, so a signature verification result is always bound to exact, immutable file contents.

If no signature verification provider is available in a region, only `SIGNATURE_ATTACHED` is asserted — never `SIGNATURE_VERIFIED`. See ADR 0007 (`docs/adr/0007-signature-verification-providers.md`).

## Deletion & Crypto-Shredding

Erasure of a FileObject means either:

- **physical deletion** of the storage object; or
- **crypto-shredding** — destroying the per-object encryption key so the stored bytes become unrecoverable.

Rules:

- every deletion sets status `DELETED` and creates a `FILE_DELETED` audit event;
- tombstoned document versions keep their hashes: locked document versions retain the file's SHA-256 hash and metadata needed for verification history, even after the file contents are erased;
- deletion of files backing locked versions follows the data subject request process, not ad-hoc removal.

## MinIO Local Development

Local Docker Compose must include:

- MinIO server;
- predefined buckets;
- local access key;
- local secret key;
- initialization script.

## Production Storage

Production storage must be chosen per region.

The application must not assume one global provider.

## AI-Agent Rule

AI agents must not introduce direct S3/MinIO calls outside the files module.
