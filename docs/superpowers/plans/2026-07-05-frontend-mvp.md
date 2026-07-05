# Frontend MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the Verifolio web frontend (`apps/frontend`) covering auth, the requester app, the recommender response flow, and the SSR public verification page, on top of the completed MVP backend API.

**Architecture:** Next.js 15 App Router behind a same-origin `/api/*` rewrite to the in-cell backend; typed `openapi-fetch` client generated from the committed OpenAPI snapshot; TanStack Query for server state; SSR only for `/verify/[token]` and static landing. Spec: `docs/superpowers/specs/2026-07-05-frontend-mvp-design.md`.

**Tech Stack:** Next.js 15, React 19, TypeScript strict, Tailwind v4, shadcn/ui (Radix), TanStack Query v5, React Hook Form + Zod, openapi-typescript + openapi-fetch, next-intl, Vitest + RTL + MSW, Playwright.

## Global Constraints

- `apps/frontend/.npmrc` with exactly `registry=https://registry.npmjs.org/` — created in Task 1, never removed (user requirement).
- Email-linked routes are fixed contracts: `/auth/callback?token=`, `/verify/[token]`, `/invitations/[token]`, `/invitations/[token]/{stop-reminders,decline,report-abuse}`.
- Presigned storage URLs: fetched on user action, used immediately, never rendered into HTML, never logged.
- No numeric trust score anywhere; trust summary = counts per category; self-declared recommender fields labeled "stated by recommender" (i18n key, both locales).
- No consent copy hardcoded: consent texts, template names/questions come from API responses.
- All user-facing strings via next-intl (`en`, `ru`) from the first component.
- Every mutating request carries `X-XSRF-TOKEN` (client middleware, Task 3) — the two public auth POSTs tolerate it being sent.
- 401 on app routes → redirect `/login`; ApiError codes map via `errorMessage(code)` (Task 3).
- Design-fidelity pass (Task 12) is gated on interactive claude_design MCP access — all other tasks proceed on `docs/DESIGN_SYSTEM.md`.

---

### Task 1: Scaffold `apps/frontend`

**Files:** Create `apps/frontend/` via `create-next-app` (TypeScript, ESLint, Tailwind, App Router, no src dir → use `app/`, import alias `@/*`); Create `.npmrc`, `.nvmrc` (`22`), `next.config.ts` (rewrites + `output: 'standalone'`), `.env.example`.

- [ ] **Step 1:** `cd apps && npx create-next-app@latest frontend --ts --eslint --tailwind --app --no-src-dir --import-alias "@/*" --use-npm`
- [ ] **Step 2:** Write `apps/frontend/.npmrc`:

```text
registry=https://registry.npmjs.org/
```

- [ ] **Step 3:** `next.config.ts`:

```ts
import type { NextConfig } from 'next'

const config: NextConfig = {
  output: 'standalone',
  async rewrites() {
    return [{ source: '/api/:path*', destination: `${process.env.BACKEND_INTERNAL_URL ?? 'http://localhost:8080'}/api/:path*` }]
  },
}
export default config
```

- [ ] **Step 4:** `.env.example` with `BACKEND_INTERNAL_URL=http://localhost:8080`, `NEXT_PUBLIC_REGION=local`. Add `apps/frontend/.env*.local` to root `.gitignore` if not covered.
- [ ] **Step 5:** `npm run build` passes; `npm run dev` + running backend: `curl -i localhost:3000/api/v1/templates` returns backend 401/200 (proxy works).
- [ ] **Step 6:** Commit — `feat(frontend): scaffold Next.js app with backend proxy and mandated .npmrc`

---

### Task 2: Design tokens, fonts, base UI kit

**Files:** Modify `app/globals.css` (Tailwind v4 `@theme` tokens from DESIGN_SYSTEM.md), `app/layout.tsx` (next/font: Inter + Source Serif 4, self-hosted); Create `components/ui/*` via `npx shadcn@latest init` + add `button card badge dialog input label textarea select checkbox form sonner skeleton`; Create `components/verifolio/badge-status.tsx`.

- [ ] **Step 1:** `@theme` tokens — exact values from DESIGN_SYSTEM.md:

```css
@theme {
  --color-ink: #0F1B2E; --color-navy: #182033; --color-paper: #F7F4EC;
  --color-warm-white: #FBFAF6; --color-blue-gray: #A5B4C4; --color-blue-gray-light: #DDE5EC;
  --color-trust-blue: #2563EB; --color-verified-green: #2EAD72; --color-muted-gold: #C8A24D;
  --color-slate-text: #475467; --color-muted-text: #667085; --color-border-light: #E4E7EC;
  --color-danger: #D92D20; --color-warning: #F79009;
  --radius-card: 16px; --radius-button: 12px;
  --shadow-card: 0 8px 24px rgb(15 27 46 / 0.06);
}
```

- [ ] **Step 2:** shadcn init (style: default, base color: neutral) then restyle `button.tsx`/`card.tsx`/`badge.tsx` variants to tokens (primary = ink bg/white text, success = verified-green — rare, danger = white bg/red text+border, secondary = white bg/border-light).
- [ ] **Step 3:** `badge-status.tsx` — the trust-badge component: `variant: 'verified'|'signed'|'locked'|'pending'|'failed'|'expired'` → DESIGN_SYSTEM badge color rules; icon + text, pill shape. Unit test: each variant renders its class set and never renders a number-only child.
- [ ] **Step 4:** `npm run test` + `npm run build` pass. Commit — `feat(frontend): Verifolio design tokens, fonts, base UI kit`

---

### Task 3: Generated API client, CSRF, errors, query layer

**Files:** Create `lib/api/schema.d.ts` (generated, committed), `lib/api/client.ts`, `lib/api/errors.ts`, `lib/query-provider.tsx`; `package.json` scripts; Test `lib/api/__tests__/client.test.ts`.

**Interfaces (produced):** `api` — `openapi-fetch` client typed by `paths`; `ApiError` type `{code: string; message: string; details?: Record<string,string>}`; `errorMessage(code: string, t: Translator): string`; `useSession()` (added in Task 4 on top of this client).

- [ ] **Step 1:** Scripts:

```json
"gen:api": "openapi-typescript ../backend/api/openapi.yaml -o lib/api/schema.d.ts",
"check:api": "openapi-typescript ../backend/api/openapi.yaml -o /tmp/schema.d.ts && diff -q /tmp/schema.d.ts lib/api/schema.d.ts"
```

- [ ] **Step 2:** `lib/api/client.ts`:

```ts
import createClient from 'openapi-fetch'
import type { paths } from './schema'

export const api = createClient<paths>({ baseUrl: '/' })

api.use({
  onRequest({ request }) {
    if (!['GET', 'HEAD'].includes(request.method)) {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
      if (m) request.headers.set('X-XSRF-TOKEN', decodeURIComponent(m[1]))
    }
    return request
  },
})
```

- [ ] **Step 3:** `errors.ts` — `KNOWN_CODES` set (`RATE_LIMITED, CONSENT_REQUIRED, CODE_INVALID, CONFIRMATION_REQUIRED, CONTACT_IN_USE, SHARE_LINK_EXPIRED, VALIDATION_FAILED, NOT_FOUND, FORBIDDEN`), `errorMessage(code, t)` → `t(\`errors.${code}\`)` if known else `t('errors.UNKNOWN', {code})`.
- [ ] **Step 4:** `query-provider.tsx` — QueryClient (staleTime 30 s, retry: skip 4xx), global `onError` → sonner toast via `errorMessage`; 401 outside `/login|/verify|/invitations|/respond` → `router.replace('/login')`.
- [ ] **Step 5:** Vitest: CSRF header attached on POST not GET (msw echo); errorMessage fallback. PASS. Commit — `feat(frontend): typed OpenAPI client with CSRF middleware and error layer`

---

### Task 4: i18n + auth screens + app shell

**Files:** Create `i18n/en.json`, `i18n/ru.json`, `lib/i18n.ts` (next-intl, cookie `NEXT_LOCALE`, no path prefix), `lib/use-session.ts`; `app/(public)/page.tsx` (landing: hero copy from DESIGN_SYSTEM, region links from `NEXT_PUBLIC_REGION_LINKS` env, login CTA), `app/(public)/login/page.tsx`, `app/(public)/auth/callback/page.tsx`, `app/(app)/layout.tsx` (dark-ink sidebar: Dashboard/Requests/Contacts/Documents/Profile + logout), `middleware.ts`; Test `app/(public)/__tests__/login.test.tsx`.

**Interfaces (produced):** `useSession(): {user, isLoading}` — query on `GET /api/v1/auth/sessions/current`.

- [ ] **Step 1:** Login form (RHF+Zod email) → `POST /auth/magic-links` → always the 202 state: "If this email has an account, a sign-in link is on its way." Rate-limit 429 → `errors.RATE_LIMITED`.
- [ ] **Step 2:** `/auth/callback` — client page: read `token` param → `POST /auth/sessions {token}` → `router.replace('/dashboard')`; error → invalid-link state with inline re-request form.
- [ ] **Step 3:** `middleware.ts` — requests to `/(app)` paths without a `verifolio_session` cookie → redirect `/login` (UX only; pages still handle 401).
- [ ] **Step 4:** App shell layout per DESIGN_SYSTEM (sidebar `--color-ink`, main bg `--color-warm-white`), locale switcher, `useSession` guard.
- [ ] **Step 5:** RTL tests: login submits and shows anti-enumeration copy on 202 AND on 429-after-toast; callback posts token. PASS. Commit — `feat(frontend): i18n, magic-link auth screens, authenticated app shell`

---

### Task 5: Contacts + Profile

**Files:** Create `app/(app)/contacts/page.tsx`, `components/contacts/contact-dialog.tsx`, `lib/hooks/use-cursor-list.ts`, `app/(app)/profile/page.tsx`; Tests for both.

**Interfaces (produced):** `useCursorList(queryKey, fetcher)` — keyset pagination (`items`, `loadMore`, `hasNext`) reused by requests/documents lists.

- [ ] **Step 1:** `use-cursor-list.ts` on `useInfiniteQuery` (`pageParam = cursor`, `getNextPageParam = last.nextCursor`).
- [ ] **Step 2:** Contacts table + create/edit dialog (name, email, relationship enum select MANAGER…OTHER) + delete with confirm; 409 `CONTACT_IN_USE` → explanatory toast ("used by a reference request").
- [ ] **Step 3:** Profile form (displayName, headline, locale select en/ru — saving locale also switches UI locale cookie).
- [ ] **Step 4:** RTL tests: dialog validation, 409 rendering, loadMore appends. PASS. Commit — `feat(frontend): contacts CRUD and profile screens`

---

### Task 6: Request builder + requests list

**Files:** Create `app/(app)/requests/page.tsx`, `app/(app)/requests/new/page.tsx`, `components/requests/{template-card,step-recommender,step-attestation,step-preview}.tsx`; Tests.

- [ ] **Step 1:** List: status filter chips (11 statuses grouped: Active / Needs review / Done / Terminal), `useCursorList`, reference cards (title, recommender, relationship, status badge, last activity).
- [ ] **Step 2:** Builder stepper (template → context/purpose → recommender: existing contact picker or inline create → attestation → preview): local state, one `POST /reference-requests` at the end. **Attestation step**: unchecked checkbox ("I confirm the recommender gave me verbal consent to receive this request") disables Continue; server 400 `CONSENT_REQUIRED` still handled. Template cards from `GET /templates?locale=`.
- [ ] **Step 3:** After create → detail page with primary action **Send** (`POST /{id}/send`; 429 → rate-limit copy).
- [ ] **Step 4:** RTL tests: attestation gating (button disabled; API not called when unchecked), template selection required, send fires from CREATED. PASS. Commit — `feat(frontend): request builder with blocking attestation and requests list`

---

### Task 7: Request detail + recipient review

**Files:** Create `app/(app)/requests/[id]/page.tsx`, `components/requests/{status-timeline,response-review}.tsx`; Tests.

- [ ] **Step 1:** Detail: status timeline (CREATED→SENT→OPENED→IN_PROGRESS→SUBMITTED→NEEDS_REVIEW→COMPLETED, terminal branches DECLINED/EXPIRED/CANCELLED), recommender snapshot fields, actions by status: send (CREATED), cancel (non-terminal), reminders state (`remindersSent`, stopped).
- [ ] **Step 2:** NEEDS_REVIEW: response review panel (approved letter text serif preview, answers, uploads list) → **Accept** (`POST /{id}/accept`, success → link to created document) or **Request correction** (dialog, optional message ≤ limit, `POST /{id}/request-correction`).
- [ ] **Step 3:** RTL tests: actions render per status only; accept success navigates; correction sends message. PASS. Commit — `feat(frontend): request detail with recipient review (accept / request correction)`

---

### Task 8: Documents + share links

**Files:** Create `app/(app)/documents/page.tsx`, `app/(app)/documents/[id]/page.tsx`, `components/documents/{share-link-dialog,version-row}.tsx`, `app/(app)/dashboard/page.tsx`; Tests.

- [ ] **Step 1:** Documents list — document cards (title, version, Locked badge, share state). Detail — versions (locked date, download via `GET .../download-url` fetched on click → `window.open(url)`; URL never stored/rendered), share links section.
- [ ] **Step 2:** Share-link create dialog: optional expiry → `POST /{id}/share-links` → **one-time raw URL display** with copy button + "You won't see this link again." List shows metadata only; revoke with confirm (`POST /share-links/{id}/revoke`, 409 double-revoke handled).
- [ ] **Step 3:** Dashboard: pending-requests (status filter SENT/OPENED/IN_PROGRESS/NEEDS_REVIEW), recent documents, empty states per DESIGN_SYSTEM warmth.
- [ ] **Step 4:** RTL tests: raw URL shown once and absent after reopen; download click calls download-url endpoint then opens. PASS. Commit — `feat(frontend): documents, share-link lifecycle, dashboard`

---

### Task 9: Recommender flow

**Files:** Create `app/(public)/invitations/[token]/page.tsx`, `.../invitations/[token]/{decline,report-abuse,stop-reminders}/page.tsx`, `app/(public)/respond/page.tsx`, `components/respond/{consent-gate,question-form,letter-editor,upload-card,confirmations-panel}.tsx`; Tests.

- [ ] **Step 1:** Invitation open page: `GET /invitations/{token}` → request summary (requester, purpose, expiry); CTA → `POST .../email-confirmations` (202) → 6-digit code input → `POST .../confirm-email` → `router.replace('/respond')`. `CODE_INVALID` with attempts left copy; resend rate-limited (3/15 min → 429 copy). 404 → single invalid/expired state.
- [ ] **Step 2:** One-click pages (`decline`, `report-abuse`, `stop-reminders`): confirm button → POST → terminal thank-you state; idempotent re-POST safe.
- [ ] **Step 3:** `/respond` under recommender session: `GET /recommender/request`; **ConsentGate first — no answer inputs mounted before accept**. Shows versioned consent text from response; jurisdiction question toggles the cross-border consent checkbox; Decline → `POST /consent {decision: DECLINED}` → terminal state. Accept → form.
- [ ] **Step 4:** `question-form` renders the template question JSON schema (text/textarea/select per schema types); autosave `PUT /response-draft` debounced 2 s with "Saved" indicator; letter editor (plain textarea, serif preview) for approved letter text.
- [ ] **Step 5:** Uploads: kind select (SCAN/SIGNED_PDF/DETACHED_SIGNATURE — the latter requires choosing a READY target from a select), `POST /recommender/uploads` → browser `fetch(putUrl, {method:'PUT', body:file, headers:{'Content-Type':type}})` → `POST .../confirm {sharedPublicly}` with the public-sharing consent copy at the toggle; delete; cap-10 and 15 MB errors surfaced.
- [ ] **Step 6:** Confirmations panel (recipient + relationship checkboxes) gates Submit → `POST /recommender/responses` → done state ("The requester will review your response"). 401 anywhere in `/respond` → resume screen: "Your session expired — confirm your email again" linking back to the invitation open state (`CONFIRMATION_REQUIRED` path).
- [ ] **Step 7:** RTL tests: no inputs before accept; signature requires target; submit disabled until confirmations; autosave debounce. PASS. Commit — `feat(frontend): recommender invitation, consent gate, response and uploads flow`

---

### Task 10: Public verification page (SSR)

**Files:** Create `app/(public)/verify/[token]/page.tsx` (server component; `fetch(\`${BACKEND_INTERNAL_URL}/api/v1/verification-pages/${token}\`, {cache:'no-store'})`), `components/verify/{trust-summary,timeline,downloads-panel}.tsx` (downloads-panel is a client island); Tests.

- [ ] **Step 1:** SSR page: header, recipient block, recommender block with "stated by recommender" label, badge list (`badge-status`), **TrustSummary as per-category counts — assert no aggregate number**, version info + `supersededByNewerVersion` notice, timeline, disclaimer + privacy notice. Backend 404 → `notFound()` → one neutral invalid/expired page.
- [ ] **Step 2:** Downloads panel (client): generated PDF always; consented attachments by filename; unconsented listed as "attachment (not shared publicly)" without filename or button. Click → download-url endpoint → immediate `window.open`; `SHARE_LINK_EXPIRED` → refresh-page prompt.
- [ ] **Step 3:** `generateMetadata` — og:title "Verified reference — Verifolio", no personal data in metadata.
- [ ] **Step 4:** RTL tests on components (counts-only summary, unconsented rendering); route test with mocked fetch for 200/404. PASS. Commit — `feat(frontend): SSR public verification page`

---

### Task 11: E2E, Docker, CI

**Files:** Create `apps/frontend/playwright.config.ts`, `e2e/canonical-flow.spec.ts`, `e2e/one-clicks.spec.ts`, `apps/frontend/Dockerfile`; Modify `docker-compose.yml` (frontend service), Create `.github/workflows/frontend.yml`; Modify `LOCAL_DEVELOPMENT.md`, `docs/TECH_STACK.md` (mark frontend delivered), `docs/agent/IMPLEMENTATION_HISTORY.md` (new iteration entry).

- [ ] **Step 1:** Playwright canonical flow using Mailpit API (`GET http://localhost:8025/api/v1/messages`) to extract magic links, invitation links, and codes: login → contact → build+send → invitation → code → consent accept → answer → submit → accept → share link → public page shows badges → revoke → invalid state. Second spec: decline + stop-reminders one-clicks.
- [ ] **Step 2:** Dockerfile (node:22-alpine, standalone output, USER node, PORT 3000); compose service `frontend` with `BACKEND_INTERNAL_URL=http://backend:8080` (add a `backend` compose service if still absent — build from `apps/backend`).
- [ ] **Step 3:** `frontend.yml`: path filter `apps/frontend/**`; job `build-test`: `npm ci && npm run lint && npm run check:api && npm run test -- --run && npm run build`; job `e2e` (non-blocking `continue-on-error: true` initially): compose up postgres/minio/mailpit/backend → `npx playwright test`.
- [ ] **Step 4:** Full suite green locally; commit — `feat(frontend): e2e canonical flow, Docker packaging, CI workflow`

---

### Task 12: Design-fidelity pass (GATED — requires interactive claude_design MCP)

**Precondition:** interactive session with `/design-login` completed; import `https://claude.ai/design/p/7fc3017f-78fe-4367-8993-acaac1b28855?file=Verifolio+Design.dc.html`.

- [ ] **Step 1:** Import the design project; inventory screens against the route map in the spec; record deltas (layout, spacing, components present in design but missing here — e.g. empty states, illustrations).
- [ ] **Step 2:** Reconcile screen-by-screen (tokens first, then per-screen layout); design wins over DESIGN_SYSTEM.md on conflict; update `docs/DESIGN_SYSTEM.md` in the same PR when values change.
- [ ] **Step 3:** Re-run unit + E2E suites; commit — `feat(frontend): design fidelity pass against Verifolio Design project`

---

## Self-review notes

- Spec coverage: every route in the spec's route map appears in Tasks 4–10; deployment/CI in Task 11; the blocker's fidelity pass in Task 12; open questions 1–6 resolved per their recommendations (minimal landing, uploads included, 2 s autosave, cookie locale, fidelity pass last, client-side dashboard).
- Fixed email routes implemented: `/auth/callback` (T4), `/invitations/[token]` + one-clicks (T9), `/verify/[token]` (T10).
- Interface consistency: `api`/`errorMessage` (T3) used from T4 on; `useCursorList` (T5) used in T6/T8; `badge-status` (T2) used in T6/T7/T8/T10.
