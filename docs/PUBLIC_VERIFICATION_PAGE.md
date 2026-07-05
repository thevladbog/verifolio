# Public Verification Page

## Purpose

The public verification page lets a third party inspect the trust evidence for a document.

It is one of the most important product surfaces.

## Access Model

The page is reachable only via recipient-created tokenized share links with expiry and revocation. Each share link is pinned to a specific document version (`ShareLink.document_version_id`). There is no other public access path, and enabling sharing is an explicit recipient action, never automatic.

## Page Goals

The page should answer:

1. Who is this document for?
2. Who confirmed it?
3. What relationship did they claim?
4. What evidence is attached?
5. Was the document changed after confirmation?
6. Can I download the supporting files?
7. What does Verifolio verify and not verify?

## Required Sections

### Header

- document title;
- document type;
- verification ID — the share-link identifier displayed on the page;
- last verified date — the date of the most recent confirmed verification signal.

There is no single "verification status" or score. Trust is presented as a signal badge list plus a derived trust summary (see Trust Summary).

### Recipient

- name;
- profile trust signals;
- name match signal if available (see `VERIFICATION_SIGNALS.md`).

### Recommender

- every field carries an explicit provenance label matching how the data actually enters
  the system: the recommender never submits their own name, so **name (and title/
  organization when shown) is labeled "provided by requester"** (it comes from the
  requester's contact entry), while **relationship is labeled "confirmed by recommender"**
  (the recommender explicitly confirms it at submission); self-declared values must never
  be presented as verified;
- email/domain verification signals.

### Trust Summary

A signal badge list plus a derived trust summary showing counts of confirmed signals by category. Badges:

- Email Confirmed;
- Corporate Domain (deny-list rules in `VERIFICATION_SIGNALS.md`);
- Recipient Confirmed;
- Relationship Confirmed;
- Scan Attached;
- Signature Attached;
- Signature Verified;
- Version Locked;
- Document Hash Recorded.

Revoked signals must not appear as confirmed.

### Document Preview

- safe PDF preview of the pinned version;
- exact version number;
- lock date;
- locked status;
- superseded marker if a newer version exists (superseded versions remain visible, never edited);
- retracted state (implemented): if the recommender retracted, the response carries `version.retractedAt` and the frontend shows "Recommendation retracted by recommender on <date>"; the locked content stays readable and signals reflect their live status (revoked signals shown as revoked). The generated PDF remains downloadable — retraction is not deletion;
- tombstoned state (implemented): if content was erased via a data subject request, the response collapses to the minimal shape `{status: "TOMBSTONED", header, notice}` with no recipient/recommender/signals/downloads/timeline; the download-url endpoints return 404. The token itself still resolves (no state oracle: unknown/revoked/expired tokens remain 404);
- translations are marked as translations; the original-language text is canonical and the recommender's accuracy attestation covers the original text only.

### Downloads

- generated PDF;
- scan;
- detached signature;
- verification certificate — a downloadable summary PDF listing the confirmed signals and their limitations.

Downloads must require share-link policy authorization. Downloads of recommender uploads (scan, signed PDF, attachments) are gated on `RECOMMENDER_PUBLIC_SHARING_CONSENT` for that upload; without it, the upload is not downloadable.

### Signature Display

A signature covers a specific uploaded file (the scan), never the generated PDF. The page must state which artifact each signature covers, for example:

```text
Signature verified for attached scan
```

### Verification Timeline

Examples:

```text
Request sent
Recommender opened
Email confirmed
Processing consent accepted
Response submitted
Recipient accepted
Scan attached
PDF generated
Version locked
Share link created
```

### Disclaimer

Recommended:

```text
Verifolio verifies identity signals, recommender confirmation methods, and document integrity. It does not independently guarantee the truth of every statement inside the document. Recommender name, title, organization, and relationship are stated by the recommender.
```

## Security Requirements

- tokenized access only, pinned to a document version;
- expiration support;
- revocation support;
- no public object URLs;
- safe file download flow;
- mandatory audit events: page views (aggregated/sampled), every download, link create/revoke/expire;
- the page must show a privacy notice covering anonymous-visitor telemetry (view tracking).

## AI-Agent Rule

Do not add public data to this page without checking privacy and share-link policy.
