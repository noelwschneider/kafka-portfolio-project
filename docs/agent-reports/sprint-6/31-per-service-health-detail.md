# Issue #31 — per-service Actuator component detail on Home's System Status table

## What changed

- `frontend/src/pages/OverviewPage.tsx` — added click-to-expand disclosure rows for each of the
  five monitored services (Order/Inventory/Payment/Fulfillment/Scenario). Each service row's name
  is now a `<button className="disclosure-toggle">` with a rotating triangle indicator
  (`aria-expanded` set correctly for accessibility). Clicking it toggles a second `<tr
  className="health-detail-row">` beneath that row, rendered by a new `ServiceComponentDetail`
  component that iterates every key in `health.raw.components` (not just `kafka`/`db`, which the
  existing `deriveInfraStatus` already special-cases for the top-level Kafka/PostgreSQL rows) and
  shows each component's own status pill. Only one service can be expanded at a time
  (`expandedService: string | null` state) — clicking a second row's toggle collapses the first.
  The derived Kafka/PostgreSQL rows are untouched (no expand affordance — they're not a single
  service's health, they're borrowed from whichever service reported the component).
- `frontend/src/index.css` — added `.disclosure-toggle`, `.disclosure-triangle` (+
  `.disclosure-open` rotation), `.health-detail-row`, `.health-component-detail`,
  `.health-component-row` rules, following the existing `.timeline-detail` dl/dt/dd pattern already
  used in `ScenarioRunDetailPage.tsx` for consistency.

No backend files touched. No new route added — this is entirely inside the existing System Status
table on `OverviewPage.tsx`.

## How this was verified

Confirmed no existing collapse/disclosure component to reuse first:

```
$ grep -rniE "collaps|disclosure|<details|expand" frontend/src -l
(no matches)
```

`ScenarioRunDetailPage.tsx` has a `dl`/`dt`/`dd` detail-row pattern (`.timeline-detail`) but no
expand/collapse interaction — so the disclosure toggle itself is new, styled to match the app's
existing `.status` pill and `.timeline-detail` monospace-dl conventions rather than inventing an
unrelated visual language.

Typecheck + production build:

```
$ cd frontend && npm run build
...
✓ built in 1.13s
```

Confirmed the target data really has more than `db`/`kafka` in it (post-#30):

```
$ curl -s localhost:8082/actuator/health
{"components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},"kafka":{"status":"UP"},
"livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"},
"ssl":{"status":"UP"}},"groups":["liveness","readiness"],"status":"UP"}
```

The full stack (all 5 business services, Kafka, Postgres, frontend, Prometheus, Grafana) was
already running from a prior session when this task started, so rebuilt only the frontend
container:

```
$ docker compose up --build -d frontend
...
 Container orderfulfillment-frontend Started
```

Visual/behavioral verification via a throwaway Playwright script (already-installed
`playwright@1.49.1` in `frontend/node_modules`; script run from `frontend/` then deleted):

```
DETAIL TEXT:
db
UP
diskSpace
UP
kafka
UP
livenessState
UP
ping
UP
readinessState
UP
ssl
UP
aria-expanded: true
health-detail-row count after switching (expect 1): 1
```

Screenshot of the expanded Inventory Service row (`http://localhost:5173/`) confirmed visually: a
disclosure triangle rotates to point down, the row expands showing all 7 Actuator components each
with their own status pill, and the pre-existing Kafka/PostgreSQL derived rows and the rest of the
page are unaffected. Clicking a second service's toggle (Payment Service) collapsed the first and
expanded the second (`health-detail-row` count stayed at 1), confirming single-row-at-a-time
behavior works as intended.

## Judgment calls

- **Single-expand vs multi-expand.** Chose only one service expanded at a time
  (`expandedService: string | null` rather than a `Set<string>`). The table only has 5 service rows
  and this is a status-glance page, not a data table meant to be fully expanded at once; keeping
  one open at a time keeps the table compact. Nothing in the issue mandated either behavior, and
  this is trivial to change to a `Set` later if wanted.
- **No expand affordance on the derived Kafka/PostgreSQL rows.** Those rows are already labeled
  "(via {source service})" — they surface one component borrowed from whichever service reported
  it, not a full health payload of their own. Adding an expand triangle there would either
  duplicate the source service's own expansion or require inventing data that doesn't exist for a
  "Kafka" or "PostgreSQL" entity as such. Left them exactly as they were.
- **Component ordering.** Rendered `Object.entries(components)` in whatever order the browser's
  `JSON.parse` preserves (insertion order from the Actuator response, which is alphabetical based
  on the observed payload). Didn't impose a custom sort — the issue only asks that every component
  be visible, not in a particular order.
- **Status coloring for arbitrary components.** Reused the existing `status-success`/`status-failure`
  two-way split (`componentClass`) rather than the three-way `status-expected` treatment used for
  the top-level Kafka/PostgreSQL "no data" case, since an individual component's status is always
  actually reported here (it's an object that exists in the map), never a designed absence — so
  there's no "no data" case to distinguish for per-component detail.

## Deliberately not covered

- Did not add expand/detail behavior to the Kafka/PostgreSQL derived rows (out of scope per the
  above judgment call and the issue's framing around per-*service* components).
- Did not re-add the removed System Health page or route — confirmed no new route was created and
  `OverviewPage.tsx` is the only file with routing-adjacent changes (none, in fact — no router
  changes were made at all).
- Did not test what happens when a service is UNREACHABLE and its `raw` is `null` end-to-end against
  a real stopped container (only exercised via the `ServiceComponentDetail`'s `!health` /
  `!components` guard paths, which are visible in the code but weren't independently forced by
  killing a container mid-verification, to avoid disrupting other in-flight work sharing the same
  docker compose stack).
- Did not add automated frontend tests (no existing test runner/config was found wired up for this
  package during this task; the verification here is build + real running-system exercise only,
  matching how the rest of `frontend/src/pages` appears to be verified in prior sprint-6 reports).
