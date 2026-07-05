-- Privacy / DSR core (docs/DATA_MODEL.md §532-569, PRIVACY_AND_DATA_CLASSIFICATION.md §246-291)
create table data_subject_request (       -- per DATA_MODEL.md §532-569
  id uuid primary key,
  type text not null check (type in ('DELETION','EXPORT','REGION_MIGRATION',
                                     'CONSENT_WITHDRAWAL','CORRECTION')),
  status text not null check (status in ('RECEIVED','IN_REVIEW','APPROVED',
                                         'EXECUTED','REJECTED')),
  region text not null,
  subject_email text not null,
  user_id uuid references user_account(id),
  recommender_contact_id uuid references recommender_contact(id),
  reference_request_id uuid references reference_request(id),  -- scope of a recommender DSR
  verified_at timestamptz,
  due_at timestamptz not null,            -- created_at + region SLA
  resolution_notes text,
  created_at timestamptz not null default transaction_timestamp(),
  updated_at timestamptz not null default transaction_timestamp(),
  constraint dsr_subject check (num_nonnulls(user_id, recommender_contact_id) = 1)
);

create table dsr_verification_code (      -- account-less recommender channel
  id uuid primary key,
  dsr_id uuid not null references data_subject_request(id),
  code_hash text not null,                -- HMAC via TokenHasher, 6 digits
  expires_at timestamptz not null,        -- 10 min
  attempts int not null default 0,        -- max 5
  consumed_at timestamptz,
  created_at timestamptz not null default transaction_timestamp()
);

alter table reference_request add column recommender_pii_erased_at timestamptz;
alter table reference_request alter column recommender_name drop not null;
alter table reference_request alter column recommender_email drop not null;
-- Recommender-PII erasure nulls the invitation-token email snapshot (erasure matrix); the
-- token is already revoked/consumed in terminal states so the email is no longer load-bearing.
alter table invitation_token alter column recommender_email drop not null;
alter table document_version add column retracted_at timestamptz;
