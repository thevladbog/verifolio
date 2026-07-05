# Roadmap

This document defines the scope cut per release stage. Agents must not build post-MVP modules without an approved task.

## MVP (EU cell first)

- Identity: email magic-link authentication — **delivered (2026-07, apps/backend)**.
- Profiles — **delivered (2026-07, apps/backend)**.
- Contacts — **delivered (2026-07, apps/backend)**.
- Request templates — **delivered (2026-07, apps/backend)**.
- Reference requests, including the consent model (per-region consent texts, cross-border transfer consent, requester attestation of verbal consent, recommender accept/decline of the processing policy) — **delivered (2026-07, apps/backend)**.
- Recommender flow — **delivered through submission (2026-07, apps/backend)**; AI letter drafting and scan/signature uploads follow with their provider/files modules.
- Documents: version locking and recipient review before locking — **delivered (2026-07, apps/backend)**; tombstoning ships with the privacy module.
- Files (private object storage through the files module) — **generated-PDF slice delivered (2026-07, apps/backend)**; user uploads (presigned PUT + validation pipeline) pending.
- Core verification signals — **creation delivered (2026-07, apps/backend)**; read model/trust summary ship with the public verification page; NAME_MATCH pending a structured recipient-name field.
- Share links and the public verification page.
- Audit events.
- Notifications.
- Minimal workflows: reminders and expiration.

## v1.1

- RU cell.
- Organizations module.
- Signature verification providers (ADR-0007).
- Admin module.
- DSR execution automation (data subject requests are accepted from MVP onward; manual execution is allowed until automation ships).
- Region migration execution.

## v2

- GLOBAL cell (or earlier per demand).
- OCR/AI assistance.
- Profile identity verification.
- Phone confirmation.

## Scope Rule

Agents must not build post-MVP modules without an approved task. If a task appears to require post-MVP scope, stop and ask.
