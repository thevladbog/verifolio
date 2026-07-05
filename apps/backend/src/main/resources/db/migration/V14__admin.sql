-- Admin foundation (docs/superpowers/specs/2026-07-05-admin-foundation-design.md §Schema).
-- Admin auth is fully isolated from user/recommender auth: separate account, magic-link,
-- session, and pending-MFA tables (mirrors the V1 identity style). Tokens/cookies are
-- HMAC-hashed via TokenHasher; TOTP secrets AES-256-GCM encrypted via AdminTotpCipher.

create table admin_account (
  id              uuid primary key default gen_random_uuid(),
  user_account_id uuid not null unique references user_account(id),
  email           text not null,                    -- denormalized for login lookup
  region          text not null,                    -- must equal the cell region
  role            text not null check (role in ('SUPPORT_L1','SUPPORT_L2','SUPERADMIN')),
  status          text not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
  totp_secret_enc text,                              -- AES-GCM(base64 iv:ct); null until enrolled
  mfa_enrolled_at timestamptz,
  created_at      timestamptz not null default transaction_timestamp(),
  updated_at      timestamptz not null default transaction_timestamp()
);
create unique index admin_account_email_unique on admin_account (lower(email));

create table admin_magic_link_token (
  id          uuid primary key default gen_random_uuid(),
  email       text not null,
  token_hash  text not null,
  expires_at  timestamptz not null,
  consumed_at timestamptz,
  invalidated_at timestamptz,
  created_at  timestamptz not null default transaction_timestamp()
);
create index admin_magic_link_token_hash_idx on admin_magic_link_token (token_hash);

create table admin_session (
  id              uuid primary key default gen_random_uuid(),
  admin_account_id uuid not null references admin_account(id),
  token_hash      text not null,
  ip_hash         text,
  user_agent_hash text,
  expires_at      timestamptz not null,
  revoked_at      timestamptz,
  last_seen_at    timestamptz,
  created_at      timestamptz not null default transaction_timestamp()
);
create index admin_session_token_hash_idx on admin_session (token_hash);

-- Pending MFA (post-magic-link, pre-session): holds the challenge state and, during
-- enrollment, the not-yet-committed secret. Short TTL, attempt-capped.
create table admin_mfa_pending (
  id              uuid primary key default gen_random_uuid(),
  admin_account_id uuid not null references admin_account(id),
  token_hash      text not null,                    -- the verifolio_admin_pending cookie value
  enroll_secret_enc text,                           -- AES-GCM; set only in ENROLL flow
  attempts        int not null default 0,
  expires_at      timestamptz not null,
  consumed_at     timestamptz,
  created_at      timestamptz not null default transaction_timestamp()
);
create index admin_mfa_pending_token_hash_idx on admin_mfa_pending (token_hash);
