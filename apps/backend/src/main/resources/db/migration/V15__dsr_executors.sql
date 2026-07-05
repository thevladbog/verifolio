-- DSR executors (docs/superpowers/specs/2026-07-05-dsr-executors-design.md §Schema).
-- Adds the EXPORT artifact purpose, the account-tombstone timestamp, and the DSR→export link.

-- Export artifact purpose. V6 defines the purpose check inline/unnamed on the `purpose`
-- column, so Postgres auto-named it `file_object_purpose_check`; drop and recreate as a
-- NAMED constraint that adds DATA_EXPORT.
alter table file_object drop constraint file_object_purpose_check;
alter table file_object add constraint file_object_purpose_check
    check (purpose in (
        'GENERATED_PDF','SCAN','DETACHED_SIGNATURE','CERTIFICATE','PREVIEW_IMAGE','ATTACHMENT',
        'DATA_EXPORT'));

-- Account tombstone marker (status is already free-text; add the terminal timestamp).
alter table user_account add column deleted_at timestamptz;

-- Link the generated export to its DSR (audit + potential admin re-fetch).
alter table data_subject_request add column export_file_id uuid references file_object(id);
