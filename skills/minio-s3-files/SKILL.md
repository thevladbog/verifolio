# MinIO S3 Files Skill

## Use When

Use this skill when implementing file upload, download, storage metadata, PDF/scans/signature files, or object storage integrations.

## Read First

- `docs/FILES_AND_STORAGE.md`
- `docs/SECURITY.md`
- `docs/REGION_POLICIES.md`
- `AGENTS.md`

## Rules

- Only files module may call object storage.
- No public object URLs.
- Store metadata and SHA-256 hash.
- Use short-lived signed URLs only after authorization.
- Keep files region-local.

## Common Mistakes

- Direct MinIO/S3 calls from documents/signatures modules.
- Storing raw URLs in document records.
- Missing file hash.
- Public buckets.

## Required Tests

- Upload flow integration test.
- Download authorization test.
- Revoked/expired link test.
- File validation test.

## Done Checklist

- [ ] FileObject metadata stored
- [ ] Hash stored
- [ ] Authorization checked
- [ ] No public URL
- [ ] Region policy checked
