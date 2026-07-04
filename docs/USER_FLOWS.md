# User Flows

## Flow 1: User Registration

1. User opens app.
2. User selects data region.
3. User enters email.
4. User receives magic link.
5. User opens magic link.
6. Backend creates session.
7. User creates profile.

## Flow 2: Create Reference Request

1. User clicks "New Request".
2. User selects template type.
3. User adds context.
4. User adds recommender details.
5. User selects verification options.
6. User checks the blocking attestation: "I confirm the recommender gave me verbal consent to receive this request." The system records a `REQUESTER_VERBAL_CONSENT_ATTESTATION` consent record and an audit event. The invitation cannot be sent without this checkbox.
7. User previews request email.
8. User sends request.
9. System starts reference request workflow (request status: `CREATED` → `SENT`).

## Flow 3: Recommender Submission

1. Recommender receives invitation email (email contains one-click stop-reminders and report-abuse links).
2. Recommender opens secure link (request status: `OPENED`). Opening the link is not identity confirmation.
3. System triggers a fresh email-confirmation step (one-time code or secondary magic link) and mints a short-lived recommender session. The invitation token is consumed single-use.
4. Recommender is shown the region's versioned consent text (152-FZ RU / GDPR EU / GLOBAL), plus cross-border transfer consent when the recommender and the storing cell are in different jurisdictions.
5. Recommender explicitly accepts (`RECOMMENDER_PROCESSING_CONSENT` recorded) or explicitly declines (request status: `DECLINED`, reminders stop, PII scheduled for erasure — see Flow 9). No answers may be collected before acceptance.
6. Recommender reviews request context (request status: `IN_PROGRESS`).
7. Recommender answers guided questions. Progress can be saved; drafts are keyed to the invitation, resuming requires re-confirmation of email, and drafts get a grace extension plus a warning email near request expiry.
8. AI drafts the letter; recommender reviews and approves the final text before submission.
9. Recommender optionally uploads scan/PDF and sets the per-upload toggle "may be shared publicly" (`RECOMMENDER_PUBLIC_SHARING_CONSENT`).
10. Recommender optionally attaches signature (signatures cover the specific uploaded scan, not the generated PDF).
11. Recommender confirms recipient and relationship.
12. Recommender submits response (request status: `SUBMITTED`).
13. System creates response and audit event, and moves the request to `NEEDS_REVIEW` for the recipient.

## Flow 4: Recipient Review and Document Generation

1. Request enters `NEEDS_REVIEW` after recommender submission.
2. Recipient reviews the submitted response.
3. Recipient chooses one of:
   - Accept: continue with steps 4–10.
   - Request correction: request status moves to `CORRECTION_REQUESTED`, then back to `IN_PROGRESS` for a new response cycle; the next accepted submission produces a new document version.
4. System generates structured document content from the recommender-approved letter text (no post-submission content changes).
5. System renders HTML.
6. System generates PDF.
7. System stores PDF in object storage.
8. System calculates file hash.
9. System creates document version and locks it.
10. System creates verification signals and moves the request to `COMPLETED`. The document becomes eligible for sharing; enabling public verification is an explicit recipient action (see Flow 5), never automatic.

## Flow 5: Public Verification

1. Recipient explicitly creates a share link pinned to a specific document version (`ShareLink.document_version_id`), with expiry and revocation support. This is the only mechanism that makes the public verification page reachable.
2. Third party opens link.
3. System validates token, expiry, and status.
4. System displays the signal badge list and derived trust summary for the pinned version.
5. Third party views document preview.
6. Third party downloads files if allowed; downloads of recommender uploads require `RECOMMENDER_PUBLIC_SHARING_CONSENT` for that upload.
7. System records mandatory audit events: page view (aggregated/sampled) and each download.

## Flow 6: Revoke Share Link

1. User opens document page.
2. User selects active share link.
3. User clicks revoke.
4. Backend revokes link.
5. Public access stops immediately.
6. Audit event is created.

## Flow 7: Profile Verification

1. User opens profile verification page.
2. User sees available trust signals.
3. User verifies email/phone/professional links.
4. User may attach digital signature or identity proof later.
5. System updates profile verification signals.
6. Public pages can show recipient trust state.

## Flow 8: Region Migration

Not supported automatically.

If needed later:

1. User requests migration.
2. System explains consequences.
3. User gives explicit consent.
4. Export package is created.
5. Target region imports data.
6. Source region handles deletion/retention policy.
7. Full audit trail is preserved.

## Flow 9: Recommender Declines a Request

1. Recommender opens the invitation link or uses the decline action during consent.
2. Recommender explicitly declines processing (or uses the one-click stop-reminders / report-abuse link in any email).
3. Request status moves to `DECLINED` (terminal).
4. All reminders stop immediately.
5. Recommender PII is scheduled for erasure.
6. Consent-declined and decline audit events are created.

## Flow 10: Recommender Retracts a Recommendation / Withdraws Consent

1. Recommender requests retraction or withdraws consent (via verified email; no account required).
2. System verifies the recommender's email.
3. Related verification signals move to `REVOKED`.
4. Public page shows "Recommendation retracted by recommender on <date>".
5. Locked versions are never edited; affected versions are marked superseded/retracted.
6. `RECOMMENDATION_RETRACTED` and consent-withdrawn audit events are created.

## Flow 11: User Submits a Data Subject Request

Available to account holders and account-less recommenders (via verified email). Request types: `DELETION`, `EXPORT`, `REGION_MIGRATION`, `CONSENT_WITHDRAWAL`, `CORRECTION`.

1. Data subject submits a DSR through the app or the recommender email channel.
2. System verifies the subject's identity (session or verified email).
3. System executes the request:
   - `DELETION`: PII erased; locked versions are tombstoned, and public pages show "content removed at data subject's request".
   - `EXPORT`: export package is created and delivered.
   - `REGION_MIGRATION`: follows Flow 8.
   - `CONSENT_WITHDRAWAL`: follows Flow 10.
   - `CORRECTION`: a new response cycle produces a new document version; old versions remain visible as superseded.
4. Every DSR step is audited.

## Canonical End-to-End Sequence

This is the canonical order of events for a verified recommendation. `WORKFLOWS.md` and `RECOMMENDER_EXPERIENCE.md` reference this sequence.

```text
1. Requester creates request + verbal-consent attestation   (CREATED)
2. Invitation sent                                          (SENT)
3. Recommender opens link                                   (OPENED)
4. Recommender confirms email (fresh confirmation step)
5. Recommender accepts processing consent                   (IN_PROGRESS)
6. Recommender answers questions, approves final letter text
7. Recommender submits                                      (SUBMITTED)
8. Recipient reviews submission                             (NEEDS_REVIEW)
9. Recipient accepts  -> PDF generation, hash, version lock,
   signal creation                                          (COMPLETED)
   Recipient requests correction -> CORRECTION_REQUESTED
   -> IN_PROGRESS (new response cycle, new document version)
10. Document becomes eligible for sharing
11. Recipient explicitly creates a share link pinned to a
    document version (public page reachable only this way)
```

Terminal alternates: `DECLINED` (recommender explicit decline), `EXPIRED` (day 21), `CANCELLED` (requester).
