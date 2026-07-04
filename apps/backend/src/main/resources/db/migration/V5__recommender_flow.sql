create table recommender_session (
    id                uuid primary key default gen_random_uuid(),
    request_id        uuid not null references reference_request (id),
    recommender_email text not null,
    token_hash        text not null unique,
    ip_hash           text,
    user_agent_hash   text,
    expires_at        timestamptz not null,
    revoked_at        timestamptz,
    created_at        timestamptz not null default now()
);
create index idx_recommender_session_request on recommender_session (request_id);

create table email_confirmation_code (
    id                  uuid primary key default gen_random_uuid(),
    invitation_token_id uuid not null references invitation_token (id),
    code_hash           text not null,
    expires_at          timestamptz not null,
    consumed_at         timestamptz,
    attempts            int not null default 0,
    created_at          timestamptz not null default now()
);
create index idx_email_confirmation_code_token on email_confirmation_code (invitation_token_id);

create table reference_response (
    id                     uuid primary key default gen_random_uuid(),
    request_id             uuid not null references reference_request (id),
    recommender_email      text not null,
    answers_json           jsonb not null default '{}'::jsonb,
    approved_letter_text   text,
    confirmation_text      text,
    relationship_confirmed boolean not null default false,
    recipient_confirmed    boolean not null default false,
    submitted_at           timestamptz,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);
-- one draft (unsubmitted row) per request; correction cycles create new rows later
create unique index uq_reference_response_draft on reference_response (request_id) where submitted_at is null;
