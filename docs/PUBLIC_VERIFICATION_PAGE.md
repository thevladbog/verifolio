# Public Verification Page

## Purpose

The public verification page lets a third party inspect the trust evidence for a document.

It is one of the most important product surfaces.

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
- verification status;
- document type;
- verification ID;
- last verified date.

### Recipient

- name;
- profile verification status;
- name match status if available.

### Recommender

- name;
- title;
- organization;
- email/domain verification;
- relationship to recipient.

### Trust Summary

Badges:

- Email Confirmed;
- Corporate Domain;
- Recipient Confirmed;
- Relationship Confirmed;
- Scan Attached;
- Signature Attached;
- Signature Verified;
- Version Locked;
- Document Hash Recorded.

### Document Preview

- safe PDF preview;
- exact version number;
- locked status.

### Downloads

- generated PDF;
- scan;
- detached signature;
- verification certificate.

Downloads must require authorization/share-link policy.

### Verification Timeline

Examples:

```text
Request sent
Recommender opened
Email confirmed
Response submitted
Scan attached
PDF generated
Version locked
Verification page enabled
```

### Disclaimer

Recommended:

```text
Verifolio verifies identity signals, recommender confirmation methods, and document integrity. It does not independently guarantee the truth of every statement inside the document.
```

## Security Requirements

- tokenized access;
- expiration support;
- revocation support;
- no public object URLs;
- safe file download flow;
- audit event on page view/download where required.

## AI-Agent Rule

Do not add public data to this page without checking privacy and share-link policy.
