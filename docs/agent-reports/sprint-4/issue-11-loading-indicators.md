# Issue #11 — loading indicators for views that fetch on mount

## What changed

- `frontend/src/components/LoadingHint.tsx` — new shared component (`<LoadingHint label="..." />`,
  renders `<p className="hint">{label}</p>`). Pulls the previously ad hoc
  `{isLoading && <p className="hint">Loading X…</p>}` pattern into one place so every page's
  loading affordance is the same component rather than independently retyped markup.
- `frontend/src/pages/CreateOrderPage.tsx` — `useQuery(['inventory'], listInventory)` now destructures
  `isLoading: inventoryLoading`. Renders `<LoadingHint label="Loading inventory…" />` above the line-items
  block while inventory is in flight, disables the SKU `<select>` during that window, and swaps its
  placeholder option text to "Loading SKUs…" instead of "Select SKU" so the empty dropdown can't be
  mistaken for "there is no inventory." This is the real gap named in the ticket — the page renders
  inline inside `OrdersListPage.tsx`'s modal (issue #7), so the loading affordance had to fit inside
  that modal's form layout rather than assume a standalone page.
- `frontend/src/pages/OverviewPage.tsx` — `useQuery(['overview-health'], fetchAllServiceHealth)` now
  destructures `isLoading: healthsLoading`; renders `<LoadingHint label="Loading service health…" />`
  above the System Status table on the initial fetch only (React Query's `isLoading` is true solely
  before the first successful fetch, not on the 10s `refetchInterval` polls afterward, so this doesn't
  flicker on every poll). Also converted the existing scenarios-loading paragraph
  (`{scenariosLoading && <p>Loading scenarios…</p>}`) to use `<LoadingHint>` for consistency.
- `frontend/src/pages/ScenarioRunDetailPage.tsx` — `useQuery(['scenarios'], listScenarios)` now
  destructures `isLoading: scenariosLoading`; renders `<LoadingHint label="Loading scenario details…" />`
  inside the `order-summary-card` when `scenariosLoading && !scenarioDefinition` is true, so the
  scenario-context block (title/description/demonstrates/expected-status) shows something instead of
  silently not rendering while `listScenarios()` is in flight. Also converted the pre-existing
  `{isLoading && <p>Loading run…</p>}` to `<LoadingHint>` for consistency. Did not touch the narrative/
  timeline logic (issue #6) or the expected/match-outcome logic (issue #12), including the existing
  "Checking actual order outcome…" `<span className="hint">` — that one is an inline status span
  inside a `<dd>`, not a block-level loading gate, so it was left as-is rather than forced into
  `LoadingHint`'s `<p>` shape.
- `frontend/src/pages/OrdersListPage.tsx` — converted the existing
  `{isLoading && <p className="hint">Loading orders…</p>}` to `<LoadingHint label="Loading orders…" />`.
- `frontend/src/pages/OrderDetailPage.tsx` — converted both existing loading lines (`Loading order…`,
  `Loading events…`) to `<LoadingHint>` for the same reason.

## How this was verified

Type check and production build:

```
$ cd frontend && npx tsc --noEmit
(no output — clean)

$ npm run build
...
✓ built in 447ms
```

Brought up the real stack (nothing was running beforehand — confirmed with `docker compose ps` first,
empty):

```
$ docker compose up --build -d
...
 Container orderfulfillment-frontend Started
 Container orderfulfillment-prometheus Started
 Container orderfulfillment-grafana Started

$ docker compose ps --format "table {{.Service}}\t{{.Status}}\t{{.Ports}}"
SERVICE               STATUS                        PORTS
frontend              Up 2 seconds                  0.0.0.0:5173->80/tcp
fulfillment-service   Up 59 seconds (healthy)       0.0.0.0:8084->8084/tcp
grafana               Up 2 seconds                  0.0.0.0:3000->3000/tcp
inventory-service     Up 59 seconds (healthy)       0.0.0.0:8082->8082/tcp
kafka                 Up About a minute (healthy)   0.0.0.0:9092->9092/tcp
order-service         Up 59 seconds (healthy)       0.0.0.0:8081->8081/tcp
payment-service       Up 59 seconds (healthy)       0.0.0.0:8083->8083/tcp
postgres              Up About a minute (healthy)   0.0.0.0:5432->5432/tcp
prometheus            Up 2 seconds                  0.0.0.0:9090->9090/tcp
scenario-service      Up 19 seconds (healthy)       0.0.0.0:8085->8085/tcp
```

Confirmed the real backend endpoints these pages hit are live and returning real data:

```
$ curl -s http://localhost:8082/api/inventory | head -c 300
[{"sku":"SKU-002","displayName":"USB-C Dock","availableQuantity":5,...

$ curl -s http://localhost:8085/demo/scenarios | head -c 300
[{"name":"standard-order","title":"Standard Fulfillment",...
```

Confirmed the container-served frontend bundle (built by `docker compose up --build`, i.e. the same
Dockerfile/vite-build path a reviewer would hit, not my local `npm run build` output) actually contains
the three new loading strings — proof the edits reached the artifact actually served, not just the
source tree:

```
$ curl -s http://localhost:5173/assets/index-CIZGOs2H.js | grep -o \
  "Loading inventory…\|Loading scenario details…\|Loading service health…"
Loading service health…
Loading inventory…
Loading scenario details…
```

Measured real round-trip latency for the two queries that gained new loading affordances, to check
whether the loading window is observable at all on this local stack:

```
$ curl -s -o /dev/null -w "inventory: %{time_total}s\n" http://localhost:8082/api/inventory (x3)
inventory: 0.047372s / 0.009382s / 0.006995s
$ curl -s -o /dev/null -w "scenarios: %{time_total}s\n" http://localhost:8085/demo/scenarios (x3)
scenarios: 0.029436s / 0.004555s / 0.003984s
```

Torn the stack back down (nothing preserved needed; no volumes touched):

```
$ docker compose down
...
 Network kafka-portfolio-project_default Removed
```

## Judgment calls

- **Extracted a shared `LoadingHint` component** rather than leaving the ad hoc `{isLoading && <p
  className="hint">...}` pattern duplicated. The ticket explicitly asked me to decide; with 8 near-
  identical one-liners across 5 pages once the 3 new ones are added, a one-line component with zero
  behavior beyond rendering a labeled `<p className="hint">` clearly paid for itself, and converting the
  5 pre-existing call sites (not just the 3 new ones) was mechanical and low-risk given they're already
  behaviorally identical.
- **Did not convert the "Checking actual order outcome…" inline `<span className="hint">` in
  `ScenarioRunDetailPage.tsx`** to `LoadingHint`. That span sits inside a `<dd>` next to other inline
  status spans (`status-success`/`status-failure`), not as a block-level "nothing has loaded yet" gate —
  forcing it into `LoadingHint`'s `<p>` shape would have meant either breaking the `<dl>` layout or adding
  a variant prop to the component for one call site. Left it as the existing, correct pattern from
  issue #12, per the scope boundary not to touch that logic.
- **`OverviewPage.tsx`'s System Status table**: added a top-level `LoadingHint` gated on `isLoading`
  (true only before the very first fetch resolves), on top of the existing per-row `stateLabel()` ===
  'checking…' fallback. Decided the per-row fallback alone was not sufficient per the ticket's framing —
  on first mount, before *any* row has data, a table with five rows all silently saying "checking…" in
  small gray badge text reads more like "nothing is happening" than the ticket's bar for "an indication
  anything is loading." A one-line hint above the table costs nothing and doesn't fight the per-row
  fallback (which remains useful once >0 rows have arrived and one is still pending, e.g. under real
  network jitter). Used `isLoading` deliberately, not `isFetching`, so the 10s poll doesn't reintroduce
  the hint on every refresh.
- **`CreateOrderPage.tsx`**: disabled the `<select>` while `inventoryLoading` is true, in addition to
  adding the `LoadingHint` line. The ticket only asked for "a loading affordance," but an enabled select
  with only a disabled placeholder option invites a confusing state (user clicks it, sees nothing to
  pick). Disabling it during the load window is a small, low-risk addition inside the same scope (the
  SKU dropdown) rather than a new surface.

## Deliberately not covered

- Did not visually observe the transient loading state in an actual browser (e.g. via DevTools network
  throttling) — no browser-automation tool was available in this session. Verified instead by: (a)
  confirming the JSX conditionals are structurally correct by reading the code, (b) confirming the exact
  new strings are present in the bundle actually served by the running `docker compose` frontend
  container (not just local build output), and (c) measuring real backend latency to establish the
  window is genuinely brief on this stack (single-digit to low-double-digit milliseconds), consistent
  with the ticket's own acknowledgment that "on a fast local stack these states are brief." A reviewer
  with browser access and network throttling enabled could confirm the visible flash directly; I could
  not in this session.
- Did not do a copy pass on any other text on these pages (headings, hints, error copy) — issue #10 is
  explicitly sequenced after this one for that reason.
- Did not touch `ArchitecturePage.tsx` — confirmed it has no data fetching, nothing to add.
- Did not touch the scenario timeline reveal/narrative logic in `ScenarioRunDetailPage.tsx` (issue #6)
  or the expected-vs-actual outcome comparison (issue #12) — both out of scope per the ticket and
  verified untouched by diff review before finishing.
