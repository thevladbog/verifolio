-- Snapshot of the contact's relationship at request creation, alongside the existing
-- name/email snapshots: the RECOMMENDER_RELATIONSHIP_CONFIRMED signal must record the
-- value the recommender actually confirmed, not the mutable contact row at accept time.
alter table reference_request add column recommender_relationship_type text;

create table file_object (
    id                   uuid primary key default gen_random_uuid(),
    bucket               text not null,
    storage_key          text not null unique,
    original_filename    text not null,
    mime_type            text not null,
    size_bytes           bigint not null,
    sha256_hash          text not null,
    purpose              text not null check (purpose in (
        'GENERATED_PDF','SCAN','DETACHED_SIGNATURE','CERTIFICATE','PREVIEW_IMAGE','ATTACHMENT')),
    status               text not null check (status in (
        'PENDING','VALIDATING','READY','REJECTED','DELETED')),
    uploaded_by_actor_id text,
    deleted_at           timestamptz,
    created_at           timestamptz not null default now()
);

create table document (
    id                 uuid primary key default gen_random_uuid(),
    owner_profile_id   uuid not null references person_profile (id),
    request_id         uuid references reference_request (id),
    type               text not null check (type in (
        'REFERENCE_LETTER','EMPLOYMENT_PROOF','IMMIGRATION_REFERENCE','VISA_SUPPORT_LETTER',
        'ACADEMIC_RECOMMENDATION','CLIENT_TESTIMONIAL','CHARACTER_REFERENCE','CUSTOM')),
    status             text not null default 'ACTIVE' check (status in ('ACTIVE')),
    current_version_id uuid,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);
create index idx_document_owner on document (owner_profile_id, created_at, id);
create unique index uq_document_request on document (request_id) where request_id is not null;

create table document_version (
    id                  uuid primary key default gen_random_uuid(),
    document_id         uuid not null references document (id),
    version_number      int not null,
    -- content columns are nullable to support future tombstoning (erasure keeps hash + metadata)
    content_json        jsonb,
    rendered_html       text,
    pdf_file_id         uuid references file_object (id),
    sha256_hash         text not null,
    status              text not null check (status in ('LOCKED','TOMBSTONED')),
    locked_at           timestamptz not null,
    locked_by_actor_id  text,
    tombstoned_at       timestamptz,
    created_at          timestamptz not null default now(),
    unique (document_id, version_number)
);

alter table document
    add constraint fk_document_current_version
    foreign key (current_version_id) references document_version (id);

create table verification_signal (
    id            uuid primary key default gen_random_uuid(),
    entity_type   text not null,
    entity_id     uuid not null,
    signal_type   text not null,
    status        text not null check (status in (
        'PENDING','VERIFIED','FAILED','EXPIRED','REVOKED','NOT_APPLICABLE')),
    evidence_json jsonb not null default '{}'::jsonb,
    provider      text,
    verified_at   timestamptz,
    expires_at    timestamptz,
    created_at    timestamptz not null default now()
);
create index idx_verification_signal_entity on verification_signal (entity_type, entity_id);
