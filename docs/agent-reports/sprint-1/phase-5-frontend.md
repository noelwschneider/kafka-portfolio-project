# Phase 5 Report — Scenario-Oriented Frontend

**Status:** frontend implementation complete against the frozen contracts available at build time.
`npm run build` (`tsc -b && vite build`) and `npm run lint` (oxlint) both pass clean. All work is
scoped to `frontend/`; no `services/` or `docs/*.md` frozen-contract file was touched.

## What was built, per page

- **Overview** (`src/pages/OverviewPage.tsx`) — System Status table for the four business services
  plus Scenario Service, each row from a real `GET {base}/actuator/health` call
  (`src/api/health.ts`), polled every 10s. Kafka/PostgreSQL rows are derived from whichever
  service's Actuator response includes a `kafka`/`db` component (`deriveInfraStatus`); if none is
  reachable the row honestly reads "no data" rather than a fabricated status. Quick Scenarios
  buttons `POST /demo/scenarios/{name}` for real and navigate to the run detail page on success.
  Recent Orders / Recent Scenario Runs lists pull from `GET /api/orders` and
  `GET /demo/scenario-runs`, each with its own honest "unreachable" fallback text rather than a
  silently empty table. One paragraph of real architecture summary (no marketing language).

- **Orders** — `OrdersListPage` and `CreateOrderPage` kept as-is (already matched the spec).
  `OrderDetailPage` rewritten to make `GET /api/orders/stream?orderId=...` (native `EventSource`,
  via the new `subscribeToStream` helper in `src/api/client.ts`) the primary live-update mechanism;
  the previous 1s poll is now a fallback that only re-engages if the stream hasn't reported "live"
  status. A small stream-state badge ("Live — updates via SSE" / "Connecting…" /
  "Live stream unavailable — falling back to polling") makes the mechanism itself visible, per the
  project's UX principle.

- **Scenarios** (`src/pages/ScenariosPage.tsx`) — cards rendered entirely from
  `GET /demo/scenarios` (`src/api/scenarios.ts`'s `listScenarios`), nothing hardcoded. Each card
  shows title/description/demonstrates/expected terminal status from the API response. `available:
  false` scenarios get a disabled button and a "Not available yet" badge instead of a button that
  does nothing. A Reset button calls `POST /demo/reset` and invalidates all queries.

- **Scenario Run Detail** (`src/pages/ScenarioRunDetailPage.tsx`) — status/elapsed/correlation
  id/subject-order-link (linking into the Orders route), timeline driven by
  `GET /demo/scenario-runs/{runId}/stream` (`timeline-entry` / `run-status` SSE events) with a
  polling fallback identical in shape to the order-detail page. Each timeline row expands to show
  metadata; the expansion renders **only** keys actually present in `detail` — `KNOWN_DETAIL_FIELDS`
  is a candidate list for nice labels, but a field absent from the payload is never shown as blank
  or zero, and any key not in that list still renders via a fallback loop so nothing is silently
  dropped either.

- **Event Explorer** (`src/pages/EventExplorerPage.tsx` + `src/api/events.ts`) — **not wired to a
  real endpoint.** See "Event Explorer dependency" below for why and what's there instead.

- **System Health** (`src/pages/SystemHealthPage.tsx`) — per-service health cards from the same
  `fetchAllServiceHealth`, each showing every component the service's Actuator response reports
  (not just a top-level badge). Consumer status is explicitly omitted with a banner explaining why
  (no frozen endpoint exposes consumer-group lag/status; `/demo/consumers` is a pause/resume
  control, not a lag readout — confirmed by reading
  `docs/agent-reports/phase-4-pattern-design.md`). "Recent errors" reflects only the current health
  snapshot, stated plainly rather than fabricating a history the system doesn't expose yet.

- **Architecture** (`src/pages/ArchitecturePage.tsx` + `src/components/MermaidDiagram.tsx`) — both
  diagrams from `docs/architecture-diagram.md` (system overview flowchart, happy-path sequence
  diagram) are rendered live via `mermaid` (added as a dependency), lazy-loaded with a dynamic
  `import('mermaid')` so its ~600KB only loads when this page is visited. Service responsibilities,
  event flow, why-Kafka, why-Kubernetes (explicitly **not yet built**, ADR-007, future tense),
  reliability notes (at-least-once only, `processed_events` idempotency, retry/DLQ, the still-open
  dual-write window outside Order Service's Phase-6 outbox), and links to the repo's own docs.

## Navigation decision

Replaced `App.tsx`'s `useState`-based view switch with **React Router** (`react-router-dom`, added
as a dependency). Seven top-level pages plus two independently-deep-linkable detail routes (order
detail, scenario-run detail) don't fit a single `view` union cleanly, and deep links / back-button
support matter for a page meant to be shared ("here's proof scenario 6 dead-lettered a record").
Routes: `/`, `/orders`, `/orders/new`, `/orders/:orderId`, `/scenarios`,
`/scenario-runs/:runId`, `/events`, `/health`, `/architecture`, with a catch-all redirect to `/`.
No global state library was added — TanStack Query remains the only server-state layer, per
`frontend-design.md` §11.

## SSE wiring

`subscribeToStream` in `src/api/client.ts` is a thin wrapper around native `EventSource` (no
libraries): takes a URL, a list of named SSE event types to listen for, and handlers; returns an
unsubscribe function. Used by both `OrderDetailPage` (`order-status-changed`) and
`ScenarioRunDetailPage` (`timeline-entry`, `run-status`). Both pages treat the stream as primary and
the existing poll as a fallback that only re-engages if the stream state isn't "live" — implemented
via `refetchInterval` reading a `streamState` value rather than removing the poll outright, so a
backend that doesn't support SSE yet still works.

## Event Explorer dependency — landed or not

**Did not land by the end of this work.** Checked `docs/openapi/scenario-service.yaml` and
`docs/CHANGELOG-contracts.md` both mid-task and again just before finishing this report; the only
changelog entry is the unrelated `inventory_items` CHECK-constraint change from Phase 4. No
event-projection query path exists in the scenario-service OpenAPI document as of this commit.

Per the task's own contingency plan: built the page's full structure and filters (event type, order
ID, correlation ID, service, topic, dead-lettered) against `frontend-design.md` §12.5 and the
envelope fields in `docs/events/event-catalog.md` §1. `src/api/events.ts`'s `queryEvents()` is
explicitly marked in a file-header comment as not wired to a real endpoint, and always returns
`{ wired: false, reason, events: [] }`; the page renders that as a visible "Not yet wired" banner
plus an honestly-empty table, never a fabricated or silently-empty result. When the endpoint lands,
only `queryEvents()`'s implementation (and the provisional `EventRecord` type, marked as
best-effort/non-frozen in the same file) should need to change — the page component shouldn't.

## What was verified live vs. structurally only

No backend service was reachable during this work (`curl` to ports 8081–8085 all failed;
`services/scenario-service/` doesn't exist yet — the sibling agent building it hadn't landed).
`services/order-service`, `-inventory-`, `-payment-`, `-fulfillment-` show in-progress
uncommitted changes from the sibling agent adding SSE/Actuator, so they were mid-edit, not a stable
build to start against; bringing up the full Kafka + Postgres + 4-service stack myself risked
colliding with that in-flight work and was out of scope for a frontend-only session, so I didn't
attempt it. This matches the task's own guidance to note rather than guess when a backend isn't up.

Verified live in the Browser tool (Vite dev server, all backends down):
- All seven pages render and navigate correctly via React Router.
- Overview, Orders, System Health, Scenarios, and Event Explorer all show real, honest
  "Unreachable" / "not yet wired" states — confirmed by reading actual rendered page text, not
  assumed.
- Architecture page's two Mermaid diagrams render as real inline SVG (not just source text).
- The dark theme (the app's existing `prefers-color-scheme` CSS) renders correctly on every new
  page and component added.

**One real bug found and fixed during this verification, worth flagging:** with TanStack Query's
default `networkMode: 'online'` and default retry backoff, a query against an unreachable backend
could sit in `fetchStatus: 'paused'` / `status: 'pending'` indefinitely with no error ever surfaced
to the user — confirmed via direct inspection of the query cache. Root cause: the query's retry
backoff relies on `setTimeout`, which the (backgrounded, unfocused) verification browser tab
throttled into never firing; the pause itself is standard `networkMode: 'online'` behavior when the
online/retry state gets stuck. Since this app's entire point is honest status reporting, an
indefinitely-silent pending state is exactly the failure mode to avoid regardless of what triggers
it. Fixed globally in `App.tsx`'s `QueryClient`: `retry: 0, networkMode: 'always'` — every query now
hits the network exactly once and reports success or failure immediately, with no retry backoff and
no dependency on the browser's online/offline signal. Confirmed fixed: after the change, every page
above showed its error state within ~1s of an unreachable backend, both in a background tab and
after a full tab close/reopen.

Not verified live (backend unavailable, noted per task instructions rather than guessed at):
- Actual `/actuator/health` response shapes (component names, whether `kafka`/`db` keys are really
  present) — the health client is written defensively against Actuator's documented default shape
  and degrades to "no data" if a component is absent, but the real shape from the sibling agent's
  work is unconfirmed.
- The SSE order-detail live-update flow end-to-end (place an order, watch status change without
  manual refresh) — code path is in place and structurally correct (verified the `EventSource`
  wrapper independently), but needs Order Service's SSE endpoint actually running to confirm.
- Scenario run execution, its SSE stream, and the Scenario Service catalog response shape —
  Scenario Service doesn't exist in the tree yet.
- The real event-projection query shape, since it isn't frozen yet (see above).

## Judgment calls

1. **React Router over continued `useState` view-switching** — argued above; the alternative was
   explicitly considered and rejected because seven pages plus two parametrized detail routes with
   deep-link/back-button expectations outgrow a single view union.
2. **Mermaid, lazy-loaded, over a static explanation** — `frontend-design.md` §12.7 left this as
   "your call." Chose real rendering because it's a direct, low-risk win for the page's whole
   purpose (make the system's shape obvious in under a minute) and the dependency is isolated to a
   dynamic import that only downloads on that one page.
3. **`retry: 0, networkMode: 'always'` globally** — not originally planned; added after the paused
   -query bug above. Kept as a deliberate choice, not just a workaround for the verification
   browser: a demo console has no good reason to hide a real failure behind automatic retries.
4. **Event Explorer's provisional data shape** — since no frozen schema exists yet, chose to shape
   `EventRecord` from the event envelope (`docs/events/event-catalog.md` §1) plus the filters
   `frontend-design.md` lists, marked non-frozen throughout, rather than leaving the page's data
   layer entirely unwritten.
5. **Kept the existing 1s poll as a fallback, not removed** — both SSE-consuming pages
   (`OrderDetailPage`, `ScenarioRunDetailPage`) keep the previous poll logic, gated on stream state,
   rather than deleting it. This satisfies "the primary mechanism should now be the stream" while
   keeping both pages correct against a backend that hasn't shipped SSE support yet, or where the
   stream drops.

## Files touched

New: `frontend/src/api/scenarios.ts`, `frontend/src/api/health.ts`, `frontend/src/api/events.ts`,
`frontend/src/components/MermaidDiagram.tsx`, `frontend/src/pages/OverviewPage.tsx`,
`frontend/src/pages/ScenariosPage.tsx`, `frontend/src/pages/ScenarioRunDetailPage.tsx`,
`frontend/src/pages/EventExplorerPage.tsx`, `frontend/src/pages/SystemHealthPage.tsx`,
`frontend/src/pages/ArchitecturePage.tsx`.

Modified: `frontend/src/api/client.ts` (new base URLs, `subscribeToStream`),
`frontend/src/App.tsx` (routing, nav, QueryClient config), `frontend/src/pages/OrderDetailPage.tsx`
(SSE), `frontend/src/index.css` (styles for all new UI), `frontend/.env.example` (three new URL
vars), `frontend/package.json` / lockfile (`react-router-dom`, `mermaid` added).
