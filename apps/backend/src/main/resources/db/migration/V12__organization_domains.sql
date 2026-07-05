-- Organizations module MVP: harden the V2 `organization` table and add a normalized
-- domain side table (docs/superpowers/specs/2026-07-05-organizations-design.md §Schema,
-- DATA_MODEL.md §Organization). The side table is the authoritative domain source for the
-- verified-organization lookup; the legacy `organization.domains` jsonb (V2) is left in place.

-- Constrain the status enum (V2 left it free text). PENDING is omitted until a real
-- verification flow exists; only VERIFIED strengthens CORPORATE_DOMAIN_CONFIRMED.
alter table organization
    add constraint organization_verification_status_check
    check (verification_status in ('UNVERIFIED','VERIFIED','REVOKED'));

-- Fast domain membership: a normalized side table beats jsonb containment for lookup and
-- enforces one-domain-one-org via the unique lower(domain) index (no ambiguous strengthening).
create table organization_domain (
    id              uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organization (id) on delete cascade,
    domain          text not null,
    created_at      timestamptz not null default transaction_timestamp()
);
create unique index organization_domain_domain_unique on organization_domain (lower(domain));
create index organization_domain_org_idx on organization_domain (organization_id);
