# Profiles + Contacts + Templates — Design

Date: 2026-07-04
Status: Approved by owner

## Goal

Second backend iteration: foundational entities that unlock the requests flow —
person profiles (auto-created at first login), recommender contacts (owner-scoped CRUD),
read-only seeded request templates, and a minimal organization entity (FK target only).

## Data (Flyway)

`V2__profiles_contacts_templates.sql`:

- `person_profile`: id (uuid pk), user_account_id (uuid FK unique), display_name,
  legal_name (nullable), preferred_locale (default 'en'),
  profile_verification_status (default 'UNVERIFIED'), created_at, updated_at.
- `organization` (minimal per ROADMAP; no API this iteration): id, name,
  domains (jsonb, default '[]'), verification_status (default 'UNVERIFIED'), timestamps.
- `recommender_contact`: id, owner_profile_id (FK person_profile), organization_id
  (nullable FK organization), name, email, company_name (nullable),
  company_domain (nullable), title (nullable), relationship_type, timestamps;
  index on owner_profile_id.
- `template`: id, type, locale, name, description, question_schema_json (jsonb),
  output_schema_json (jsonb), required_fields_json (jsonb),
  verification_recommendations_json (jsonb), timestamps, unique(type, locale).

`V3__seed_templates.sql`: seeds the six system templates (locale `en`) from
`docs/REQUEST_TEMPLATES.md`, including "not for public display" field markers and
verification recommendations. RU locale versions come with the RU cell (v1.1).
`CUSTOM` type is not seeded (defined at request creation, later iteration).

## Behavior

- **Profile auto-creation**: identity publishes a `UserAccountCreated` application event
  when `consumeMagicLink` creates a new account; the profiles module consumes it via
  Spring Modulith `@ApplicationModuleListener` and creates an empty profile
  (display_name defaults to the email local part). No direct identity→profiles dependency.
- `GET /api/v1/profile` — current user's profile (404 PROFILE_NOT_FOUND only in the
  pathological pre-listener window; listener is transactional-after-commit, tests cover it).
- `PUT /api/v1/profile` — update display_name, legal_name, preferred_locale. Audited.
- Contacts (all owner-scoped; access to another owner's contact → 404 NOT_FOUND):
  - `GET /api/v1/contacts` — cursor pagination `{items, nextCursor}` (cursor = created_at+id keyset, page size 50);
  - `POST /api/v1/contacts` — name + email required; relationship_type from enum;
  - `GET/PUT/DELETE /api/v1/contacts/{id}` — delete is a hard delete (contact PII), audited.
- `relationship_type` enum (canonical, added to DATA_MODEL.md): MANAGER, COLLEAGUE,
  DIRECT_REPORT, CLIENT, PROFESSOR, MENTOR, PERSONAL, OTHER.
- Templates: `GET /api/v1/templates?locale=en` (default en; list, no pagination — bounded set),
  `GET /api/v1/templates/{id}`. Read-only; no write API.

## Audit

New events (added to docs/AUDIT_EVENTS.md): PROFILE_CREATED, PROFILE_UPDATED,
CONTACT_CREATED, CONTACT_UPDATED, CONTACT_DELETED. Template reads are not audited:
templates contain no PII and reads cross no authorization boundary (rationale recorded
in AUDIT_EVENTS.md).

## API Contract

OpenAPI snapshot updated (endpoints, schemas, pagination envelope, error responses
401/404/400 with ApiError). Codes: NOT_FOUND, VALIDATION_ERROR, UNAUTHORIZED.

## Testing

TDD; integration tests (Testcontainers, existing testsupport base):
- login of a new user auto-creates a profile; PROFILE_CREATED audited;
- profile GET/PUT round-trip; PUT audited;
- contacts CRUD; ownership isolation (user B gets 404 for user A's contact);
- pagination (keyset, >50 items → nextCursor works);
- templates: 6 seeded en templates listed; get by id; unknown id → 404;
- ModularityTests stay green (16 modules; event-driven identity→profiles link).

## Out of Scope

Organizations API, professional links, profile verification signals, contact
communication preferences and invitation state, CUSTOM template authoring,
RU template locales, requests flow.

## Deliverables at End

Docs: AUDIT_EVENTS.md (new events + template-read rationale), DATA_MODEL.md
(relationship_type enum note), IMPLEMENTATION_HISTORY.md append, ROADMAP.md marks,
apps/backend/README.md module list update, refreshed api/openapi.yaml.
