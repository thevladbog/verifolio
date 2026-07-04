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

Links that are added but not verified are labeled "self-reported".

Verification methods:

- OAuth sign-in with the linked platform (where the platform supports it);
- domain/page ownership proof via a meta tag or `rel="me"` link pointing back to the Verifolio profile.

Link verification beyond these two methods is post-MVP.

### Name Consistency

System checks whether document recipient names match the profile name via the `NAME_MATCH` signal defined in `VERIFICATION_SIGNALS.md`, which specifies:

- match levels;
- transliteration handling;
- user-first surfacing (the user sees the result before anyone else);
- the dispute path.

Non-strong match states are not shown publicly.

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
Name match signal (per NAME_MATCH in VERIFICATION_SIGNALS.md; only strong matches shown publicly)
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
