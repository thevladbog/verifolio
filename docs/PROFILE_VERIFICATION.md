# Profile Verification

## Purpose

Profile verification increases confidence that documents belong to the person sharing them.

It must be optional and progressive.

## Principles

- Do not create a heavy KYC wall in v1.
- Start with lightweight trust signals.
- Show what was verified and how.
- Do not expose sensitive identity data publicly.
- Store all profile verification data regionally.

## Initial Trust Signals

### Email Verified

User confirmed primary email.

### Phone Verified

Optional later.

### Professional Links

User can add:

- LinkedIn;
- GitHub;
- personal website;
- portfolio;
- company profile.

### Name Consistency

System checks whether document recipient names match profile name.

### Recipient Confirmed by Recommender

Recommender confirms that the document is about the profile owner.

## Later Signals

- government ID verification;
- digital signature profile statement;
- passkey-backed identity signal;
- verified professional domain;
- enterprise/organization verification.

## Public Display

Public page may show:

```text
Recipient profile: Verified email
Recipient confirmed by recommender
Name match: strong
```

It must not show:

- passport number;
- identity document scans;
- raw phone number unless user allows;
- sensitive identity provider data.

## Profile Statement

Future feature:

User signs a statement:

```text
I confirm that this Verifolio profile belongs to me.
```

Then uploads signature evidence.

## AI-Agent Rule

Do not add identity verification methods without privacy/data classification review.
