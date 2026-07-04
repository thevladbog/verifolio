# Request Templates

## Purpose

Templates are a core product feature.

They guide users and recommenders toward useful, structured, purpose-specific references.

## Template Structure

```text
Template
- type
- locale
- name
- description
- requester_questions
- recommender_questions
- required_fields
- optional_fields
- output_sections
- verification_recommendations
```

Templates may mark individual fields as "not for public display" (for example, company contact information). Such fields are stored and available to the recipient and document workflows but never rendered on public verification pages.

## Employment Reference

Goal: confirm professional experience and performance.

Suggested questions:

- What was the candidate's role?
- When did you work together?
- What was your relationship?
- What projects did the candidate work on?
- What were their main responsibilities?
- What strengths did they demonstrate?
- Would you recommend them for similar roles?

Verification recommendations:

- corporate domain confirmed;
- relationship confirmed;
- recipient confirmed by recommender.

## Immigration Reference

Goal: provide factual employment/professional experience confirmation.

Suggested questions:

- Exact employment dates?
- Job title?
- Full-time, part-time, or contract?
- Main duties?
- Technologies/tools used?
- Reporting line?
- Projects/products?
- Company contact information? (not for public display)
- Can this be printed on letterhead?

Verification recommendations:

- corporate domain confirmed;
- letterhead scan attached;
- signature attached and verified;
- relationship confirmed.

Because immigration references are typically presented to government authorities, this template recommends a minimum signal set: corporate domain confirmed, letterhead scan attached, and signature attached.

## Visa Support Letter

Goal: support a professional or business visa application.

Suggested questions:

- Purpose of travel?
- Dates?
- Role of applicant?
- Relationship to inviting/supporting organization?
- Who covers expenses?
- Contact person? (not for public display)
- Is company letterhead available?

Verification recommendations:

- corporate domain confirmed;
- letterhead scan attached;
- signature attached and verified.

Because visa support letters are typically presented to government authorities, this template recommends a minimum signal set: corporate domain confirmed, letterhead scan attached, and signature attached.

## Academic Recommendation

Goal: support university, scholarship, fellowship, or academic application.

Suggested questions:

- How do you know the applicant?
- What academic/professional qualities stand out?
- Examples of analytical ability?
- Examples of discipline or independence?
- Comparison with peers?
- Suitability for the target program?

Verification recommendations:

- corporate/academic domain confirmed;
- relationship confirmed;
- letterhead scan attached (optional).

## Client Testimonial

Goal: confirm delivered work and client satisfaction.

Suggested questions:

- Project scope?
- Delivered result?
- Quality of work?
- Communication?
- Reliability?
- Deadlines?
- Would you hire/recommend again?

Verification recommendations:

- email confirmed;
- corporate domain confirmed (when the client is an organization);
- relationship confirmed.

## Character Reference

Goal: confirm personal reliability, trust, and reputation.

Suggested questions:

- How long have you known the person?
- In what context?
- What qualities can you confirm?
- Examples of responsibility or integrity?
- Would you recommend them?

Verification recommendations:

- email confirmed;
- relationship confirmed;
- recipient confirmed by recommender.

## AI Assistance

AI may help:

- draft request text;
- improve clarity;
- generate formal letter from structured answers;
- translate;
- detect missing details.

AI must not create verification evidence.

## AI-Agent Rule

When adding a template, include required fields, output sections, verification recommendations, and tests.
