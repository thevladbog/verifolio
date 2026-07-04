# Product Requirements

## Product Vision

Verifolio helps people collect verified professional references and proof documents before they need them.

The product should make it easy to:

- request the right document;
- guide recommenders through useful questions;
- verify source and integrity signals;
- store documents securely;
- share evidence with third parties;
- keep data inside the selected region.

## Primary Users

### Requester / Profile Owner

A person collecting references and proof documents.

Goals:

- collect recommendations;
- confirm experience;
- prepare for job applications;
- prepare immigration/visa/university/client packages;
- share trusted evidence.

### Recommender

A person confirming a reference.

Goals:

- understand what is requested;
- provide useful answers;
- confirm relationship;
- attach scan/signature if available;
- submit without creating a full account.

### Third-Party Verifier

An employer, university, consultant, client, or authority reviewing the document.

Goals:

- see who the document is for;
- see who confirmed it;
- see what evidence is attached;
- download files;
- understand limitations.

### Admin / Support

Internal operator helping with abuse, support, and operational issues.

## Core MVP Capabilities

- user registration/login through magic link;
- region selection;
- profile creation;
- template-based request creation;
- recommender invitation;
- guided recommender response;
- scan/PDF upload;
- document generation;
- document version locking;
- verification signals;
- public verification page;
- audit events;
- file storage in regional object storage.

## Template Types

- Employment Reference;
- Immigration Reference;
- Visa Support Letter;
- Academic Recommendation;
- Client Testimonial;
- Character Reference;
- Custom Request.

## Trust Signals

The product should support multiple trust signals rather than one binary verified flag.

Examples:

- Email Confirmed;
- Corporate Domain Confirmed;
- Recipient Confirmed;
- Recommender Relationship Confirmed;
- Scan Attached;
- Signature Attached;
- Signature Verified;
- Version Locked;
- Identity Verified;
- Public Verification Enabled.

## Non-Goals for MVP

- blockchain;
- global identity wallet;
- mandatory KYC;
- enterprise SSO;
- full legal signature validation in all countries;
- marketplace of recommenders;
- ATS integrations;
- public social network;
- AI as verification source.

## Product Tone

The product should be:

- clear;
- calm;
- transparent;
- professional;
- not bureaucratic;
- not over-promising.

Preferred trust message:

```text
Verifolio shows what was verified, how it was verified, and when. It does not guarantee the truth of every statement inside the document.
```
