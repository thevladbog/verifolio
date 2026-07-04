create table person_profile (
    id                          uuid primary key default gen_random_uuid(),
    user_account_id             uuid not null unique references user_account (id),
    display_name                text not null,
    legal_name                  text,
    preferred_locale            text not null default 'en',
    profile_verification_status text not null default 'UNVERIFIED',
    created_at                  timestamptz not null default now(),
    updated_at                  timestamptz not null default now()
);

create table organization (
    id                  uuid primary key default gen_random_uuid(),
    name                text not null,
    domains             jsonb not null default '[]'::jsonb,
    verification_status text not null default 'UNVERIFIED',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create table recommender_contact (
    id                uuid primary key default gen_random_uuid(),
    owner_profile_id  uuid not null references person_profile (id),
    organization_id   uuid references organization (id),
    name              text not null,
    email             text not null,
    company_name      text,
    company_domain    text,
    title             text,
    relationship_type text not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);
create index idx_recommender_contact_owner on recommender_contact (owner_profile_id, created_at, id);

create table template (
    id                                uuid primary key default gen_random_uuid(),
    type                              text not null,
    locale                            text not null,
    name                              text not null,
    description                       text not null,
    question_schema_json              jsonb not null,
    output_schema_json                jsonb not null,
    required_fields_json              jsonb not null default '[]'::jsonb,
    verification_recommendations_json jsonb not null default '[]'::jsonb,
    created_at                        timestamptz not null default now(),
    updated_at                        timestamptz not null default now(),
    unique (type, locale)
);
