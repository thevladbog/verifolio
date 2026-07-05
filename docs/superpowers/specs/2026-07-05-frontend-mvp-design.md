# Frontend MVP — Design

Date: 2026-07-05
Status: draft — awaiting user approval
Scope: the web frontend for the completed MVP backend (PRs #12–#17): authenticated
requester app, recommender response flow, public verification page, auth screens.
No backend changes. Post-MVP surfaces (admin, organizations, DSR automation UI) are out
of scope.

## Blocker: design project import

The user's design source of truth is the claude.ai design project
`https://claude.ai/design/p/7fc3017f-78fe-4367-8993-acaac1b28855?file=Verifolio+Design.dc.html`
("Verifolio Design.dc.html"), to be imported via the **claude_design MCP**
(`https://api.anthropic.com/v1/design/mcp`, auth via `/design-login`).

**In this (background) session the claude_design MCP is not connected and cannot be
authenticated non-interactively; a direct fetch of the project URL returns 403.**
Consequences:

- This spec proceeds on `docs/DESIGN_SYSTEM.md` (the committed brand/design system:
  palette, type scale, radii, shadows, component inventory) as the interim visual source
  of truth. It is detailed enough to build the full token layer and all screens.
- A dedicated **design-fidelity pass** is scheduled as the final implementation phase:
  once the design project is imported in an interactive session (`/design-login`), each
  screen is reconciled against `Verifolio Design.dc.html`. Layout/visual deltas are
  expected; API contracts, routes, and component structure are not.
- If the imported design contradicts `DESIGN_SYSTEM.md`, the imported design wins for
  visuals; `DESIGN_SYSTEM.md` gets a follow-up update PR.

## Placement: monorepo `apps/frontend`

Decision: the frontend lives in this repository at **`apps/frontend/`**, next to
`apps/backend/`.

Rationale: the repo already uses the `apps/*` convention; the frontend consumes the
committed OpenAPI snapshot `apps/backend/api/openapi.yaml` (typed client regenerated in
the same PR that changes the contract — drift is caught at build time, impossible with a
second repo without publishing pipelines); one PR can ship a contract change end-to-end;
CI is path-filtered exactly like `backend.yml`. A separate repository adds versioning
and coordination cost with zero MVP benefit.

Per the user's requirement, `apps/frontend/.npmrc` is created with the standard npm
registry:

```text
registry=https://registry.npmjs.org/
```

## Stack (per ADR-0006, concretized)

| Concern | Choice | Notes |
|---|---|---|
| Framework | Next.js 15 (App Router) + React 19 + TypeScript (strict) | SSR for public pages per ADR-0006 |
| Styling | Tailwind CSS v4 | tokens from DESIGN_SYSTEM.md as CSS `@theme` variables |
| Primitives | shadcn/ui (Radix UI) | vendored components, restyled to Verifolio tokens |
| Forms | React Hook Form + Zod | zodResolver; Zod schemas colocated with forms |
| Server state | TanStack Query v5 | no client-side global store; auth state = `currentSession` query |
| API client | `openapi-typescript` (types) + `openapi-fetch` (runtime) | generated from `apps/backend/api/openapi.yaml`; committed output + `check:api` drift script |
| i18n | next-intl | locales `en`, `ru` (backend allowlist); messages per locale JSON |
| Fonts | Inter (UI), Source Serif 4 (document previews) | via `next/font`, self-hosted — no external font CDN calls from regional cells |
| Icons | lucide-react | outline style per DESIGN_SYSTEM.md |
| Unit tests | Vitest + React Testing Library + MSW | MSW handlers derived from generated types |
| E2E | Playwright | against docker-compose backend + Mailpit API for magic links/codes |
| Lint/format | ESLint (next/core-web-vitals) + Prettier | |
| Package manager | npm | plus the mandated `.npmrc` |

Rejected within ADR-0006's frame: hand-written fetch layer (contract drift),
`@hey-api/openapi-ts` client classes (heavier than needed; `openapi-fetch` is ~2 kB and
maps 1:1 to paths), Redux/Zustand (server cache + URL state covers MVP), axios.

## Architecture

### Same-origin API proxy

The browser talks only to the frontend origin; Next.js rewrites `/api/*` to the
regional backend (`BACKEND_INTERNAL_URL`, in-cell address):

```js
// next.config.ts
rewrites: [{ source: '/api/:path*', destination: `${process.env.BACKEND_INTERNAL_URL}/api/:path*` }]
```

Why: `verifolio_session` / `verifolio_recommender_session` cookies are
`SameSite=Strict` + `HttpOnly` — a cross-origin API domain would break them; same-origin
also makes the XSRF-TOKEN cookie readable and removes CORS entirely. The backend stays
private inside the cell; **no object-storage URLs pass through the proxy** (presigned
URLs are fetched via API and used directly by the browser, exactly as the backend
intends — they are short-lived (5 min) and never rendered into public HTML).

### Rendering strategy

- **SSR (per-request)**: public verification page `/verify/[token]` — first-load
  performance and link previews (ADR-0006). Rendered by the in-cell Next server; page
  data comes from the in-cell backend; `robots: noindex` is NOT set (public page), but
  tokens never appear in logs. The `download-url` endpoints are called client-side on
  click only (presigned URLs must not be embedded in HTML).
- **Static**: landing `/`, region-selection copy, legal pages.
- **Client-rendered (behind auth)**: everything under `/(app)` and the recommender flow
  — data is personal, SSR gains nothing; TanStack Query owns fetching. Next
  middleware does a cheap cookie-presence check for `/(app)` routes and redirects to
  `/login` (real authorization stays in the backend — the frontend never bypasses
  domain authorization, it only improves UX).

### Session, CSRF, errors

- Session bootstrap: `GET /api/v1/auth/sessions/current` as the root `useSession()`
  query; 401 → redirect to `/login`.
- CSRF: read `XSRF-TOKEN` cookie, send `X-XSRF-TOKEN` header on every mutating request
  — implemented once as an `openapi-fetch` middleware.
- Errors: backend `ApiError{code,message,details}` is the single error shape. A
  `code → i18n message` map covers known codes (`RATE_LIMITED`, `CONSENT_REQUIRED`,
  `CODE_INVALID`, `CONFIRMATION_REQUIRED`, `CONTACT_IN_USE`, `SHARE_LINK_EXPIRED`, …);
  unknown codes fall back to a generic message + the raw code. 429 surfaces a calm
  "try again later" banner (magic links, email confirmations, sends are rate-limited).

### Regional model

One frontend build, deployed per cell (`app.eu.verifolio.com`, later
`app.ru.verifolio.com`); cell identity comes only from env
(`NEXT_PUBLIC_REGION`, `BACKEND_INTERNAL_URL`). The landing offers region selection as
plain links to regional app domains (stateless global layer, ADR-0008 — no email→region
directory). The Next server calls only its own cell's backend; no third-party
runtime services (fonts self-hosted, no external analytics in MVP) — no regional data
leaves the cell.

## Route map ↔ API mapping

Routes marked ✉ are **fixed contracts** — the backend emails link to them verbatim.

### Public / auth

| Route | Screen | API |
|---|---|---|
| `/` | Landing + region selection | — |
| `/login` | Magic-link request form | `POST /auth/magic-links` (202 always — anti-enumeration copy: "If this email exists, a link is on its way") |
| ✉ `/auth/callback?token=` | Consumes magic link → session | `POST /auth/sessions` → redirect `/dashboard`; invalid/expired → error state + re-request form |
| ✉ `/verify/[token]` | Public verification page (SSR) | `GET /verification-pages/{token}` (404 → single "link invalid or expired" state — no oracle); downloads: `GET .../download-url`, `GET .../attachments/{id}/download-url` on click |

Public verification page sections mirror the backend read model exactly: header,
recipient block, recommender block **labeled "stated by recommender"**, badge list,
trust summary as **counts per category — never a single number/score/percentage**,
version info (incl. `supersededByNewerVersion`), timeline, downloads (attachments
without granted public-sharing consent are listed without filename and not
downloadable), disclaimer + privacy notice.

### Recommender flow (`/invitations/[token]/…`, ✉ all)

| Route | Screen | API |
|---|---|---|
| `/invitations/[token]` | Open invitation: request summary → triggers email confirmation | `GET /invitations/{token}` (open), `POST .../email-confirmations` (202), `POST .../confirm-email` (code form → recommender session cookie) |
| `/invitations/[token]/decline` | One-click decline (confirm button, then done-state) | `POST .../decline` |
| `/invitations/[token]/report-abuse` | One-click report abuse | `POST .../report-abuse` |
| `/invitations/[token]/stop-reminders` | One-click stop reminders | `POST .../stop-reminders` (idempotent) |

After email confirmation the flow continues under the recommender session at
`/respond` (session-scoped, no token in URL):

| Route | Screen | API |
|---|---|---|
| `/respond` | Consent gate → guided questions → letter text → uploads → confirmations → submit | `GET /recommender/request`, `POST /recommender/consent` (accept/decline; decline = terminal thank-you state), `PUT /recommender/response-draft` (autosave, debounced), uploads below, `POST /recommender/responses` |

Consent gate rules honored in UI: **no answer inputs render before an explicit
accept**; the region's versioned consent text is displayed; the cross-border-transfer
consent checkbox is shown based on a client-side jurisdiction question (backend records
what is granted; `local` cell requires processing consent only). Question form is
rendered dynamically from the template question schema (JSON schema from
`GET /recommender/request`). Uploads: `POST /recommender/uploads` (constrained presigned
PUT: browser PUTs the file directly to storage with the signed content-type/length)
→ `POST /recommender/uploads/{id}/confirm` (with per-upload `sharedPublicly` toggle →
public-sharing consent copy shown at the toggle), `DELETE /recommender/uploads/{id}`;
kinds SCAN / SIGNED_PDF / DETACHED_SIGNATURE (signature requires selecting a READY
target upload). Recipient + relationship confirmation checkboxes gate submit
(`CONFIRMATION_REQUIRED`). Session expiry (1 h) → "confirm your email again" resume
screen (drafts are keyed to the request; re-confirmation restores them).

### Authenticated app (`/(app)` group, dark-sidebar shell)

| Route | Screen | API |
|---|---|---|
| `/dashboard` | Trust overview, pending requests, recent documents/activity | `GET /reference-requests?status=…`, `GET /documents` (composed client-side; no dedicated dashboard endpoint in MVP) |
| `/requests` | Requests list (status filter, keyset cursor "Load more") | `GET /reference-requests` |
| `/requests/new` | Request builder: template cards → context → recommender (pick contact or create) → **blocking verbal-consent attestation checkbox** → preview → create | `GET /templates?locale=`, `GET/POST /contacts`, `POST /reference-requests` (400 `CONSENT_REQUIRED` if unchecked — button also disabled client-side) |
| `/requests/[id]` | Request detail: status timeline, actions send / cancel / remind-state; **NEEDS_REVIEW: response review → Accept or Request correction (with optional message)** | `GET /reference-requests/{id}`, `POST .../send`, `.../cancel`, `.../accept`, `.../request-correction` |
| `/contacts` | Contacts CRUD (list + create/edit dialogs; delete → `CONTACT_IN_USE` 409 explained) | `/contacts` CRUD |
| `/documents` | Documents list (cards with badges) | `GET /documents` |
| `/documents/[id]` | Document detail: versions, locked badge, PDF download, share links (create with optional expiry, list, revoke) | `GET /documents/{id}`, `GET .../versions/{n}/download-url`, `POST .../share-links`, `GET .../share-links`, `POST /share-links/{id}/revoke` |
| `/profile` | Profile view/edit (display name, headline, locale en/ru) | `GET/PUT /profile` |
| `/logout` (action) | | `DELETE /auth/sessions/current` |

Share-link create dialog shows the raw URL **exactly once** (backend returns it only at
creation) with copy button + "you won't see this link again" notice. Trust/badge
components implement DESIGN_SYSTEM.md badge color rules; **no numeric trust score
anywhere**; `CompletenessHint` (builder) is owner-only and never framed as trust.

## i18n

- Locales: `en` (default), `ru` — matching the backend profile-locale allowlist.
  Locale selection: profile locale for authenticated users; `Accept-Language` +
  manual switcher for public/recommender pages (a recommender in the EU cell may be
  Russian-speaking; UI language is independent of data region).
- All UI copy through next-intl messages from day one; voice per DESIGN_SYSTEM.md
  ("This document was confirmed by the recommender" — never "100% authentic").
- Consent texts, template names/questions come from the backend (already localized
  there) — the frontend never hardcodes consent copy.

## Deployment & CI

- `apps/frontend/Dockerfile` — multi-stage, `output: 'standalone'`, non-root, port 3000.
- `docker-compose.yml` gains a `frontend` service (build from `apps/frontend`,
  `BACKEND_INTERNAL_URL=http://backend:8080`) — completing the LOCAL_DEVELOPMENT
  service list. Local dev without Docker: `npm run dev` + running backend on :8080.
- CI: `.github/workflows/frontend.yml`, path-filtered on `apps/frontend/**` (mirror of
  `backend.yml`): `npm ci && npm run lint && npm run check:api && npm run test &&
  npm run build`. Playwright E2E as a separate non-blocking job initially (needs the
  compose stack), promoted to blocking once stable.
- Production: per-cell Node deployment (ADR-0006 consequence) — same host/cluster as
  the backend cell; not a global edge/CDN deployment (SSR of personal data must stay
  in-cell).

## Testing strategy

- **Unit/component (Vitest + RTL + MSW)**: form validation (attestation gating, submit
  confirmation gating), error-code rendering, badge color mapping, cursor pagination
  hook, CSRF middleware, dynamic question-schema renderer.
- **E2E (Playwright)**: the canonical end-to-end sequence (USER_FLOWS.md) as one
  scenario — login via Mailpit-fetched magic link → create contact → build+send request
  → open invitation from Mailpit → confirm code → consent → respond → submit → accept →
  share link → public page renders badges → revoke → public page 404. Plus decline and
  stop-reminders one-clicks.
- No screenshot/visual tests until the design-fidelity pass lands.

## Non-negotiables checked

- No object-storage URLs in HTML/logs; presigned URLs fetched on demand, used
  immediately (files module rules respected — frontend only ever sees presigned URLs
  from the API).
- No auth bypass: middleware is UX-only; every page tolerates backend 401/403/404.
- No regional data leaves the cell: same-origin proxy, in-cell SSR, self-hosted fonts,
  no third-party runtime calls.
- Locked versions immutable: UI offers no edit affordances on locked versions;
  correction flow only via `request-correction`.
- Consent surfaces: attestation checkbox blocks creation; recommender consent gate
  renders before any inputs; per-upload public-sharing toggle carries its consent copy.
- Trust display: badge lists + per-category counts only; self-declared fields labeled
  "stated by recommender".

## Open questions (recommendation first — none block the plan)

1. **Landing scope.** Recommendation: minimal calm landing (hero + region selection +
   login CTA) in this iteration; the full marketing page later. Alternative: skip `/`
   entirely and redirect to `/login`.
2. **Recommender uploads in first frontend release.** Recommendation: include (backend
   is done and it is an MVP differentiator). Alternative: text-only responses first,
   uploads as fast-follow, if review speed matters more.
3. **Draft autosave cadence.** Recommendation: debounce 2 s + explicit "Saved" indicator
   (`PUT /response-draft` is idempotent). Alternative: manual save button only.
4. **Locale routing.** Recommendation: cookie/profile-based locale, no `/en|/ru` URL
   prefix (public verify links stay clean and stable). Alternative: path prefix — better
   for SEO of the landing only; can be added later for `/` alone.
5. **Design-fidelity pass timing.** Recommendation: run after all functional screens
   exist (single reconciliation pass, needs interactive `/design-login`). Alternative:
   import design first and block implementation on it — rejected: MCP unavailable in
   background sessions (see Blocker), and DESIGN_SYSTEM.md is sufficient to build
   correctly-structured screens.
6. **Dashboard composition.** MVP has no dashboard aggregate endpoint; recommendation:
   compose from list endpoints client-side and defer a `GET /dashboard` backend
   endpoint until it hurts. Alternative: add the endpoint now (backend change —
   out of this scope).
