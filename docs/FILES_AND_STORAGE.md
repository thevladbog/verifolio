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
- uploaded_by_actor_id
- created_at
```

## Object Key Strategy

Recommended object key format:

```text
{region}/{profile_id}/{document_id}/{document_version_id}/{file_id}/{safe_filename}
```

Example:

```text
eu/profile_123/doc_456/version_1/file_789/recommendation.pdf
```

Do not include raw personal names or emails in object keys.

## Upload Flow

Recommended flow:

1. Frontend asks backend for upload permission.
2. Backend validates actor and purpose.
3. Backend creates pending FileObject metadata.
4. Backend returns pre-signed upload URL.
5. Client uploads to object storage.
6. Backend confirms upload.
7. Backend calculates or verifies hash.
8. Backend marks FileObject as ready.
9. Backend creates audit event.

## Download Flow

Recommended flow:

1. Client requests file download.
2. Backend checks domain authorization.
3. Backend checks share link state if public.
4. Backend creates short-lived pre-signed download URL.
5. Backend creates audit event if needed.

## File Validation

Minimum validation:

- size limit;
- allowed MIME types;
- extension check;
- content sniffing;
- antivirus scan where available;
- hash calculation;
- region policy check.

## File Immutability

A FileObject is immutable after upload confirmation.

If a file changes, it must be stored as a new FileObject.

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
