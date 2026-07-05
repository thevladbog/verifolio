create table share_link (
    id                  uuid primary key default gen_random_uuid(),
    document_id         uuid not null references document (id),
    document_version_id uuid not null references document_version (id),
    token_hash          text not null unique,
    visibility          text not null default 'PUBLIC' check (visibility in ('PUBLIC')),
    expires_at          timestamptz,
    revoked_at          timestamptz,
    created_at          timestamptz not null default now()
);
create index idx_share_link_document on share_link (document_id);
