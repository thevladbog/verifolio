create table reference_request (
    id                     uuid primary key default gen_random_uuid(),
    requester_profile_id   uuid not null references person_profile (id),
    recommender_contact_id uuid not null references recommender_contact (id) on delete restrict,
    template_id            uuid not null references template (id),
    purpose                text,
    status                 text not null default 'CREATED'
        check (status in ('CREATED','SENT','OPENED','IN_PROGRESS','SUBMITTED','NEEDS_REVIEW',
                          'CORRECTION_REQUESTED','COMPLETED','DECLINED','EXPIRED','CANCELLED')),
    expires_at             timestamptz not null,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);
create index idx_reference_request_owner on reference_request (requester_profile_id, created_at, id);

create table consent_record (
    id                     uuid primary key default gen_random_uuid(),
    subject_type           text not null check (subject_type in ('REQUESTER','RECOMMENDER')),
    user_id                uuid references user_account (id),
    recommender_contact_id uuid references recommender_contact (id),
    reference_request_id   uuid references reference_request (id) on delete restrict,
    consent_type           text not null check (consent_type in (
        'REQUESTER_VERBAL_CONSENT_ATTESTATION','RECOMMENDER_PROCESSING_CONSENT',
        'RECOMMENDER_PUBLIC_SHARING_CONSENT','CROSS_BORDER_TRANSFER_CONSENT')),
    policy_text_version    text not null,
    region                 text not null,
    status                 text not null check (status in ('GRANTED','DECLINED','WITHDRAWN')),
    granted_at             timestamptz,
    declined_at            timestamptz,
    withdrawn_at           timestamptz,
    created_at             timestamptz not null default now(),
    -- subject_type determines which identifier is present (DATA_MODEL.md attribution constraint)
    constraint chk_consent_subject check (
        (subject_type = 'REQUESTER' and user_id is not null and recommender_contact_id is null) or
        (subject_type = 'RECOMMENDER' and recommender_contact_id is not null and user_id is null)
    )
);
create index idx_consent_record_request on consent_record (reference_request_id);

create table invitation_token (
    id                uuid primary key default gen_random_uuid(),
    request_id        uuid not null references reference_request (id),
    recommender_email text not null,
    token_hash        text not null unique,
    expires_at        timestamptz not null,
    consumed_at       timestamptz,
    revoked_at        timestamptz,
    created_at        timestamptz not null default now()
);
create index idx_invitation_token_request on invitation_token (request_id);
