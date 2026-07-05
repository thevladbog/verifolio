# Frontend Tasks 1–7 — Interim Review (commits eb3eb8c..d20aa39)

Date: 2026-07-05. Read-only review while implementation continues; apply fixes after
Task 11 or before the PR.

## CRITICAL

1. **`@radix-ui/react-dropdown-menu` missing from `apps/frontend/package.json`** —
   imported by `components/ui/dropdown-menu.tsx:4` but only resolvable via an
   accidental untracked npm install at the worktree ROOT (`package.json`,
   `package-lock.json`, `node_modules/` — contain exactly this one dep). Clean
   `npm ci && next build` in `apps/frontend` (CI/Docker, Task 11) will fail.
   Fix: add the dependency to `apps/frontend/package.json`; delete the root
   `package.json`/`package-lock.json`/`node_modules` (never commit them).

## IMPORTANT

2. Root `package.json`/`package-lock.json`/`node_modules` are accidental (see #1) —
   delete, do not commit.
3. `/dashboard` doesn't exist yet — `auth/callback` redirects there (login dead-ends
   on 404). Expected to close with Task 8; verify before merge.
4. Plan Task 4 Step 2 deviation: no fresh-profile onboarding (design 8a — empty
   `displayName` → name/headline/locale step via `PUT /profile`) in the auth callback;
   also expired-link state links to `/login` instead of an inline re-request form.
   Implement or consciously drop with a plan note.

## MINOR

5. `requests/[id]/page.tsx:114` renders `null` on 403/404 — add a not-found state.
6. `lib/requests/queries.ts:38` `useContactNames` caps at 4 pages — builder picker
   silently omits contacts beyond ~200; needs search/fallback eventually.
7. `lib/requests/status.ts:36-38` — SUBMITTED/NEEDS_REVIEW use the `locked` badge
   variant; semantically the document-lock signal. Revisit in Task 12 fidelity pass.
8. `NEXT_PUBLIC_REGION_LINKS` (landing) missing from `.env.example`.
9. Task 4 plan said dark-ink sidebar; implementation is a top header nav ("per design
   canvas") — fine if the design confirms it, document the deviation.
10. Task 6 components implemented inline in `requests/new/page.tsx` instead of the
    planned `components/requests/*` split — functional parity, structure deviates.

## Conformance (PASS for committed scope)

.npmrc exact; same-origin proxy; CSRF middleware on all mutations; generated client
byte-identical to `apps/backend/api/openapi.yaml`; en/ru key sets identical +
anti-enumeration copy; attestation gating client+server; Manrope/Source Serif 4
self-hosted; tokens match DESIGN_SYSTEM.md; cookie names consistent; no presigned
URLs rendered/stored/logged in committed code (re-review documents/share-links when
Task 8 lands); no numeric trust score.
