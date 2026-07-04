# Privacy and Data Classification

## Purpose

This document classifies data handled by Verifolio and defines handling rules.

## Data Categories

### Public Data

Examples:

- marketing site content;
- product documentation;
- public brand assets.

Handling:

- can be global;
- can be cached/CDN-hosted.

### Account Data

Examples:

- email;
- phone;
- login events;
- session metadata;
- profile name.

Handling:

- region-local;
- minimized in logs;
- access controlled.

### Professional Profile Data

Examples:

- display name;
- work history fields;
- professional links;
- trust signals.

Handling:

- region-local;
- user-controlled visibility.

### Reference Data

Examples:

- recommender name;
- recommender email;
- relationship;
- answers;
- letter text.

Handling:

- region-local;
- sensitive;
- strict access control.

### Document Files

Examples:

- generated PDF;
- uploaded scan;
- signature file;
- certificates.

Handling:

- private object storage;
- region-local;
- hash stored;
- no public object URLs.

### Verification Metadata

Examples:

- verification signal;
- timestamp;
- provider;
- evidence summary.

Handling:

- region-local;
- may be public only on authorized verification page.

### Audit Data

Examples:

- actor ID;
- action;
- entity ID;
- timestamp;
- IP hash;
- user-agent hash.

Handling:

- append-only;
- region-local;
- minimized.

### Highly Sensitive Data

Examples:

- identity verification documents;
- passport data;
- raw personal IDs;
- biometric/liveness data.

Handling:

- avoid storing in v1;
- use external regional providers if needed;
- store only verification result where possible;
- require separate security/privacy review.

## Logging Rules

Never log:

- raw tokens;
- passwords;
- full document text;
- file contents;
- private download URLs;
- raw identity documents.

## Data Minimization

Collect only what is required for the selected workflow.

## Retention

Retention policies must be defined per region and data type.

## User Controls

Users should be able to:

- revoke share links;
- delete drafts;
- request export;
- request deletion where applicable;
- control public visibility.

## AI-Agent Rule

Do not introduce new data collection without classifying the data.
