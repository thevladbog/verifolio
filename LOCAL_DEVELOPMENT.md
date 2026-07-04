# Local Development

> Status: backend commands under `apps/backend` are real and functional. Frontend (`apps/web`) commands remain target-state (not yet bootstrapped).

## Goal

Local development should be production-like while remaining easy to start.

## Required Services

Recommended Docker Compose services:

```text
postgres
minio
temporal      # temporalio/auto-setup
temporal-ui   # temporalio/ui
mailpit
backend
frontend
```

Temporal runs in Docker Compose using the `temporalio/auto-setup` image (server, gRPC on 7233) plus the `temporalio/ui` image (web UI on 8088; the backend keeps 8080).

Optional:

```text
redis
local-ocr
local-ai
```

## Local URLs

Suggested defaults:

```text
Frontend: http://localhost:3000
Backend: http://localhost:8080
PostgreSQL: localhost:5432
MinIO API: http://localhost:9000
MinIO Console: http://localhost:9001
Temporal gRPC: localhost:7233
Temporal UI: http://localhost:8088
Mailpit: http://localhost:8025
```

## Environment Variables

Example:

```env
APP_REGION=local
DATABASE_URL=jdbc:postgresql://localhost:5432/verifolio
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minio
MINIO_SECRET_KEY=minio123
TEMPORAL_ADDRESS=localhost:7233
MAIL_HOST=localhost
MAIL_PORT=1025
SESSION_COOKIE_SECURE=false
```

`APP_REGION=local` is development-only and must never be used as a production value. The production region registry lives in `docs/REGION_POLICIES.md`.

## Start

```bash
docker compose up -d
```

Backend (runs on port 8080; JDK 21+ required — see `apps/backend/README.md`):

```bash
cd apps/backend
./gradlew bootRun
```

Frontend (target state — not yet bootstrapped):

```bash
cd apps/web
npm install
npm run dev
```

## Migrations

Flyway migrations run automatically at startup. To regenerate jOOQ sources after a
schema change:

```bash
cd apps/backend
./gradlew generateJooq
```

Generated sources land in `build/generated-jooq` and are never committed.

## jOOQ Generation

After schema changes:

```bash
cd apps/backend
./gradlew generateJooq
```

## Tests

```bash
cd apps/backend
./gradlew test
```

## MinIO Setup

Local buckets should be initialized automatically.

Recommended buckets:

```text
verifolio-private
verifolio-temp
```

No bucket should be public.

## Mail Testing

Use Mailpit for magic links, invitations, and reminders. The Mailpit web UI is
available at `http://localhost:8025`. The backend sends mail to Mailpit's SMTP
listener on port 1025.

Do not send local emails through production mail providers.

## Common Local Checks

- Can user request magic link?
- Can user log in?
- Can user create reference request?
- Can recommender open invitation?
- Can file upload to MinIO?
- Can generated PDF be stored?
- Can verification page load through backend?
