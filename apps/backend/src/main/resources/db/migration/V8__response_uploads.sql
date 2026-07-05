create table response_upload (
    id                uuid primary key default gen_random_uuid(),
    request_id        uuid not null references reference_request (id),
    file_object_id    uuid not null unique references file_object (id),
    kind              text not null check (kind in ('SCAN','SIGNED_PDF','DETACHED_SIGNATURE','ATTACHMENT')),
    -- a detached signature covers a specific uploaded file, never the generated PDF
    target_upload_id  uuid references response_upload (id),
    shared_publicly   boolean not null default false,
    consent_record_id uuid references consent_record (id),
    created_at        timestamptz not null default now()
);
create index idx_response_upload_request on response_upload (request_id);

create table document_attachment (
    id                  uuid primary key default gen_random_uuid(),
    document_version_id uuid not null references document_version (id),
    file_object_id      uuid not null references file_object (id),
    type                text not null check (type in ('SCAN','SIGNED_PDF','DETACHED_SIGNATURE','ATTACHMENT')),
    created_at          timestamptz not null default now(),
    -- retries of attachFiles must not duplicate links (public downloads list every row)
    unique (document_version_id, file_object_id)
);
create index idx_document_attachment_version on document_attachment (document_version_id);
