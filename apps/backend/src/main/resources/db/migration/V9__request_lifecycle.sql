-- Reminder schedule anchor and state (docs/WORKFLOWS.md Reminder Policy)
alter table reference_request add column sent_at timestamptz;
alter table reference_request add column reminders_sent int not null default 0;
alter table reference_request add column reminders_stopped_at timestamptz;
