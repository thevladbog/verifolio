-- Curator-replaceable starter set; the real registry grows via admin curation (deferred).
-- RU/GLOBAL cells seed their own lists.
--
-- A small curated set of well-known VERIFIED organizations (uncontroversial large employers
-- by public primary domain), mirroring the template-seed approach in V3. Each entry is one
-- `organization` row (verification_status='VERIFIED') plus its `organization_domain` rows.
-- The domains are also mirrored into the legacy `organization.domains` jsonb for consistency;
-- `organization_domain` is the authoritative source for the lookup. Fixed UUIDs make the
-- seeded orgs deterministically referenceable from tests.

insert into organization (id, name, domains, verification_status) values
    ('a0000000-0000-4000-8000-000000000001', 'SAP SE',                    '["sap.com","successfactors.com"]'::jsonb, 'VERIFIED'),
    ('a0000000-0000-4000-8000-000000000002', 'Siemens AG',                '["siemens.com"]'::jsonb,                  'VERIFIED'),
    ('a0000000-0000-4000-8000-000000000003', 'Spotify AB',                '["spotify.com"]'::jsonb,                  'VERIFIED'),
    ('a0000000-0000-4000-8000-000000000004', 'ING Groep N.V.',            '["ing.com"]'::jsonb,                      'VERIFIED'),
    ('a0000000-0000-4000-8000-000000000005', 'Koninklijke Philips N.V.',  '["philips.com"]'::jsonb,                  'VERIFIED'),
    ('a0000000-0000-4000-8000-000000000006', 'Adyen N.V.',                '["adyen.com"]'::jsonb,                    'VERIFIED'),
    ('a0000000-0000-4000-8000-000000000007', 'Booking.com B.V.',          '["booking.com"]'::jsonb,                  'VERIFIED'),
    ('a0000000-0000-4000-8000-000000000008', 'Deutsche Bank AG',          '["db.com"]'::jsonb,                       'VERIFIED');

insert into organization_domain (organization_id, domain) values
    ('a0000000-0000-4000-8000-000000000001', 'sap.com'),
    ('a0000000-0000-4000-8000-000000000001', 'successfactors.com'),
    ('a0000000-0000-4000-8000-000000000002', 'siemens.com'),
    ('a0000000-0000-4000-8000-000000000003', 'spotify.com'),
    ('a0000000-0000-4000-8000-000000000004', 'ing.com'),
    ('a0000000-0000-4000-8000-000000000005', 'philips.com'),
    ('a0000000-0000-4000-8000-000000000006', 'adyen.com'),
    ('a0000000-0000-4000-8000-000000000007', 'booking.com'),
    ('a0000000-0000-4000-8000-000000000008', 'db.com');
