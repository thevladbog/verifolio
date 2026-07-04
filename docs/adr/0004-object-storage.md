# ADR 0004: Object Storage

## Status

Accepted

## Context

Verifolio stores PDFs, scans, detached signature files, certificates, and previews.

Files must be private, region-local, hashable, and linked to document versions.

## Decision

Use S3-compatible object storage behind an internal abstraction.

Use MinIO for local development.

Production storage is selected per region.

## Consequences

Positive:

- provider flexibility;
- local production-like development;
- clean file metadata model;
- supports regional deployments.

Negative:

- more application code for file lifecycle;
- upload/download flows require careful security controls.
