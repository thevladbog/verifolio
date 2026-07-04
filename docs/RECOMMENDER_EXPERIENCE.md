# Recommender Experience

## Goal

The recommender experience should be simple, respectful, and low-friction.

A recommender should not feel forced to create an account or complete a bureaucratic process.

## Flow

This flow follows the canonical end-to-end sequence in `USER_FLOWS.md`.

1. Receive invitation email (every email includes a one-click stop-reminders link and a report-abuse link).
2. Open secure link. Opening the link is not identity confirmation.
3. Confirm email via a fresh confirmation step (one-time code or secondary magic link). This mints a short-lived recommender session; the invitation token is consumed single-use.
4. Accept or decline the processing consent (see Consent Gate below). Declining ends the flow: the request becomes `DECLINED`, reminders stop, and the recommender's PII is scheduled for erasure.
5. Review request context.
6. Answer guided questions.
7. AI drafts the letter; review and approve the final text — no post-submission content changes to the approved text.
8. Optionally upload scan/PDF and choose per upload whether it "may be shared publicly".
9. Optionally attach signature (a signature covers the specific uploaded scan, never the generated PDF).
10. Confirm recipient and relationship.
11. Submit. The recipient then reviews the submission in `NEEDS_REVIEW` and either accepts it or requests a correction. On acceptance, exactly the approved letter text is rendered into the final PDF and the document version is locked. A correction starts a new response cycle and produces a new document version.

## Consent Gate

Before any answers are collected, the recommender must explicitly accept the region's versioned processing consent text (152-FZ RU / GDPR EU / GLOBAL):

- acceptance records `RECOMMENDER_PROCESSING_CONSENT` and is audited;
- an explicit decline moves the request to `DECLINED`, stops reminders, and schedules PII erasure;
- when the recommender and the storing cell are in different jurisdictions, a separate cross-border transfer consent is presented;
- the consent screen discloses that the recommender's data is stored in the recipient's region, and that invitation open/interaction tracking exists;
- an optional `RECOMMENDER_PUBLIC_SHARING_CONSENT` covers public verification pages and downloadable copies of the recommender's uploads; it is controlled by a per-upload toggle "may be shared publicly". Without it, uploads are never publicly downloadable.

## Decline and Anti-Spam

- The recommender can explicitly decline the request at the consent gate or via email links.
- Every recommender email includes a one-click stop-reminders link and a report-abuse link.
- Reminders stop immediately on decline, submission, stop-reminders click, or abuse report.
- A global per-recommender-email rate limit applies across all requesters.

## UX Principles

- Explain why the request exists.
- Show who requested the reference.
- Show how the response will be used.
- Avoid legalistic language (consent texts must still be legally accurate for the region).
- Make optional verification steps clear.
- Do not overpromise legal effect.
- Allow saving progress (see Saving Progress).
- Make submission confirmation explicit.

## Saving Progress

- Drafts are keyed to the invitation.
- Resuming a draft requires re-confirmation of the recommender's email.
- Drafts near request expiry get a grace extension and a warning email.

## Confirmation Checkboxes

Recommended:

```text
- I confirm that this recommendation is for [Recipient Name].
- I confirm that the information I provided is accurate to the best of my knowledge.
- I confirm my relationship to the recipient as [Relationship].
- I understand that this response may be stored in Verifolio and shared by the recipient.
```

If the letter is translated, the accuracy attestation covers the original-language text only; the original-language answers are canonical and translations are marked as such.

## Optional Uploads

- scan on letterhead;
- signed PDF;
- detached signature file;
- supporting attachment.

Each upload has a "may be shared publicly" toggle. Public pages and downloads include an upload only when the recommender granted `RECOMMENDER_PUBLIC_SHARING_CONSENT` for it.

## Recommender Account

No full account required in v1.

The invitation token plus the fresh email-confirmation step provide scoped, short-lived access.

## Retraction and Consent Withdrawal

A recommender can retract a recommendation or withdraw consent at any time, without an account, via verified email:

- related verification signals move to `REVOKED`;
- the public page shows "Recommendation retracted by recommender on <date>";
- locked versions are never edited; affected versions are marked superseded/retracted;
- the action is audited (`RECOMMENDATION_RETRACTED`).

Recommenders also have a data subject request channel (via verified email) for `DELETION`, `EXPORT`, `REGION_MIGRATION`, `CONSENT_WITHDRAWAL`, and `CORRECTION` requests.

## AI Assistance

AI drafts the letter in the recommender UI; the recommender reviews and approves the final text before submission. The workflow renders exactly that approved text into the PDF. Both the structured answers and the approved letter text are stored.

AI may help the recommender:

- format answers into a letter draft;
- improve grammar;
- make the letter clearer;
- translate (original-language answers remain canonical; translations are marked as translations).

AI must not invent facts.

## AI-Agent Rule

Do not add friction to recommender flow unless required for security or product reasons.
