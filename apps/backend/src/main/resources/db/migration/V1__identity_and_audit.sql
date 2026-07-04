create table user_account (
    id          uuid primary key default gen_random_uuid(),
    email       text not null unique,
    region      text not null,
    status      text not null default 'ACTIVE',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table magic_link_token (
    id             uuid primary key default gen_random_uuid(),
    email          text not null,
    token_hash     text not null unique,
    expires_at     timestamptz not null,
    consumed_at    timestamptz,
    invalidated_at timestamptz,
    created_at     timestamptz not null default now()
);
create index idx_magic_link_token_email on magic_link_token (email);

create table user_session (
    id              uuid primary key default gen_random_uuid(),
    user_account_id uuid not null references user_account (id),
    token_hash      text not null unique,
    ip_hash         text,
    user_agent_hash text,
    expires_at      timestamptz not null,
    revoked_at      timestamptz,
    created_at      timestamptz not null default now()
);
create index idx_user_session_account on user_session (user_account_id);

create table audit_event (
    id              uuid primary key default gen_random_uuid(),
    actor_type      text not null,
    actor_id        text,
    action          text not null,
    entity_type     text,
    entity_id       text,
    metadata        jsonb not null default '{}'::jsonb,
    ip_hash         text,
    user_agent_hash text,
    created_at      timestamptz not null default now()
);
create index idx_audit_event_action on audit_event (action);
