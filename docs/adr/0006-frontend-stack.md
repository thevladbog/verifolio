# ADR 0006: Frontend Stack

## Status

Accepted

## Context

Verifolio needs a frontend for:

- the authenticated profile/requests application;
- the recommender response flow;
- public verification pages served from regional cells.

Public verification pages benefit from server-side rendering (fast first load, link previews), and the backend exposes a REST + OpenAPI contract (ADR 0001), so a typed generated client is preferred over hand-written fetch code.

Regional deployment (ADR 0003) means the frontend is deployed inside each regional cell; rendering of pages containing personal data must not happen outside the cell.

## Considered Options

1. **Next.js + React + TypeScript.**
   SSR/SSG for public pages, mature ecosystem, one framework for app and public pages.
2. **Plain React SPA (Vite).**
   Simpler deployment (static assets), but public verification pages would be client-rendered, hurting first-load and link previews, or require a second rendering solution.
3. **Server-rendered templates from Spring.**
   No separate frontend deployment, but weak interactive-UX ecosystem and poor separation between API contract and views.

## Decision

Use:

- Next.js;
- React;
- TypeScript;
- Tailwind CSS;
- TanStack Query;
- generated OpenAPI client.

Radix UI / shadcn-style primitives, React Hook Form, and Zod remain the recommended supporting libraries.

SSR implications: the frontend is deployed per regional cell, and public verification pages are rendered within the user's cell. The Next.js server never runs in a central location that would process regional personal data.

## Consequences

Positive:

- server-rendered public verification pages with good performance and link previews;
- one stack for the app and public pages;
- type safety end-to-end via the generated OpenAPI client;
- large ecosystem and hiring pool.

Negative:

- the frontend is a server-side deployment per cell, not just static assets;
- Next.js version upgrades require ongoing maintenance;
- SSR data fetching must respect the same regional boundaries as the backend.
