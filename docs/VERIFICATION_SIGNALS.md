# Verification Signals

## Purpose

Verification signals describe specific evidence about a user, recommender, document, file, organization, or signature.

Verifolio must not use a vague `verified = true` model.

## VerificationSignal Model

```text
VerificationSignal
- id
- entity_type
- entity_id
- signal_type
- status
- evidence_json
- provider
- verified_at
- expires_at
- created_at
```

## Statuses

```text
PENDING
VERIFIED
FAILED
EXPIRED
REVOKED
NOT_APPLICABLE
```

## Core Signal Types

### EMAIL_CONFIRMED

Meaning: the actor confirmed access to an email address.

Evidence:

- email domain;
- confirmation timestamp;
- token purpose;
- provider.

Public text:

```text
Email address was confirmed.
```

Limitation:

```text
Email confirmation proves access to the mailbox, not identity or authority.
```

### CORPORATE_DOMAIN_CONFIRMED

Meaning: recommender email belongs to a corporate/domain email.

Evidence:

- email domain;
- domain match;
- organization name if available.

Public text:

```text
Corporate domain was confirmed.
```

Limitation:

```text
Domain confirmation does not prove the person is authorized to speak on behalf of the company.
```

### RECIPIENT_CONFIRMED

Meaning: recommender confirmed that the document is about the named recipient.

Public text:

```text
Recommender confirmed the recipient.
```

### RECOMMENDER_RELATIONSHIP_CONFIRMED

Meaning: recommender stated and confirmed their relationship to the recipient.

Examples:

```text
Direct Manager
Peer
Client
Professor
HR Representative
```

### SCAN_ATTACHED

Meaning: a scan or PDF was attached.

Public text:

```text
Source scan was attached.
```

Limitation:

```text
A scan can increase confidence but does not automatically prove authenticity.
```

### SIGNATURE_ATTACHED

Meaning: a signature file was attached.

Public text:

```text
Digital signature file was attached.
```

### SIGNATURE_VERIFIED

Meaning: the attached signature was successfully verified by a supported provider/process.

Evidence:

- signature format;
- certificate subject;
- certificate issuer;
- verification timestamp;
- file hash.

Limitation:

```text
Signature verification proves file integrity and certificate ownership where applicable. It does not independently guarantee that every statement in the document is true.
```

### VERSION_LOCKED

Meaning: document version was locked and cannot be silently modified.

Public text:

```text
Document version is locked.
```

### DOCUMENT_HASH_LOCKED

Meaning: file/document hash was calculated and stored.

Public text:

```text
Document hash was recorded.
```

### IDENTITY_VERIFIED

Meaning: user profile identity was verified through an approved process.

This may be implemented later.

### PUBLIC_VERIFICATION_ENABLED

Meaning: document has an active public verification page or share link.

## Public Display Rules

Each signal shown publicly must include:

- plain-language name;
- verified/failed/expired state;
- date;
- explanation;
- limitation if necessary.

## Adding New Signals

When adding a signal:

- define meaning;
- define evidence;
- define who/what can create it;
- define expiration behavior;
- define public display text;
- define limitations;
- add audit event;
- add tests.

## AI-Agent Rule

Do not add new signal types without updating this document.
