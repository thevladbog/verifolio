# Roadmap

This document defines the scope cut per release stage. Agents must not build post-MVP modules without an approved task.

## MVP (EU cell first)

- Identity: email magic-link authentication — **delivered (2026-07, apps/backend)**.
- Profiles — **delivered (2026-07, apps/backend)**.
- Contacts — **delivered (2026-07, apps/backend)**.
- Request templates — **delivered (2026-07, apps/backend)**.
- Reference requests, including the consent model (per-region consent texts, cross-border transfer consent, requester attestation of verbal consent, recommender accept/decline of the processing policy) — **delivered (2026-07, apps/backend)**.
- Recommender flow — **delivered (2026-07, apps/backend)** including scan/signed-PDF/detached-signature uploads with the per-upload public-sharing toggle; AI letter drafting follows with its provider module.
- Documents: version locking and recipient review before locking — **delivered (2026-07, apps/backend)**; tombstoning ships with the privacy module.
- Files (private object storage through the files module) — **delivered (2026-07, apps/backend)**: generated PDFs plus recommender uploads via constrained presigned PUT with synchronous confirm-validation; antivirus/async pipeline ships with Temporal.
- Core verification signals — **creation delivered (2026-07, apps/backend)**; read model/trust summary ship with the public verification page; NAME_MATCH pending a structured recipient-name field.
- Share links and the public verification page — **delivered (2026-07, apps/backend)**; verification certificate PDF and scan/signature sections ship with their features.
- Audit events.
- Notifications.
- Minimal workflows: reminders and expiration — **delivered (2026-07, apps/backend)** on the ADR-0005 DB-scheduler fallback (Temporal remains the target engine). **This completes the MVP backend feature set.**

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
