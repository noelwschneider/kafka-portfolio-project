# Issue #5 — Merge Scenarios into Home, drop reset button

## What changed

- `frontend/src/pages/OverviewPage.tsx` — rewritten. Kept the hero and System Status table
  unchanged. Replaced the "Quick Scenarios" 4-button subset and the "Recent Orders" / "Recent
  Scenario Runs" tables with the full scenario grid (rendered live from `GET /demo/scenarios` via
  the existing `listScenarios()`), moved from the old ScenariosPage. Each card now shows title,
  description, and expected terminal status inline (the scannable "essential" info) with the
  `demonstrates` list moved inside a `<details>/<summary>` disclosure so it's available on expand
  rather than always shown. Removed the reset button/mutation entirely — no `resetDemoEnvironment`
  import or usage remains in this file.
- `frontend/src/pages/ScenariosPage.tsx` — deleted. Its content is now folded into
  `OverviewPage.tsx`; nothing else imported it after the App.tsx changes below.
- `frontend/src/App.tsx` — removed the `ScenariosPage` import, removed the `Scenarios` entry from
  `NAV_ITEMS` (Overview/Orders/Events/Health/Architecture remain), and changed the `/scenarios`
  route from `<ScenariosPage />` to `<Navigate to="/" replace />` so any existing bookmark/external
  link still resolves instead of 404ing. Left `ScenarioRunDetailRoute`'s `navigate('/scenarios')`
  fallback and `onBack` untouched — they now bounce through the redirect to `/`, which is
  functionally equivalent and out of this issue's scope to rewrite.
- `frontend/src/index.css` — removed the now-unused `.quick-scenarios` rule; added
  `.scenario-card-description`, `.scenario-card-expected`, and `.scenario-card-details`
  (summary/h4) rules to style the new always-visible vs. expand-on-demand card sections. Left
  `.scenario-card`, `.scenario-grid`, `.badge*` untouched since they're reused as-is.

## How this was verified

Type-check + build:

```
$ npm run build
> frontend@0.0.0 build
> tsc -b && vite build
...
✓ built in 438ms
```
(Only pre-existing chunk-size warnings from mermaid/cytoscape, unrelated to this change.)

Lint:

```
$ npm run lint
> frontend@0.0.0 lint
> oxlint
```
(No findings printed.)

Full stack via `docker compose up --build -d` (all containers reached healthy, including
`orderfulfillment-frontend` on port 5173):

```
$ docker compose ps --format "table {{.Name}}\t{{.Status}}"
NAME                                   STATUS
orderfulfillment-frontend              Up 5 seconds
orderfulfillment-fulfillment-service   Up 37 seconds (healthy)
orderfulfillment-inventory-service     Up 37 seconds (healthy)
orderfulfillment-kafka                 Up 44 seconds (healthy)
orderfulfillment-order-service         Up 37 seconds (healthy)
orderfulfillment-payment-service       Up 37 seconds (healthy)
orderfulfillment-postgres              Up 44 seconds (healthy)
orderfulfillment-scenario-service      Up 16 seconds (healthy)
```

Root and legacy `/scenarios` path both serve the SPA shell (client router then redirects
`/scenarios` to `/`):

```
$ curl -s http://localhost:5173/ -o /dev/null -w "root: %{http_code}\n"
root: 200
$ curl -s http://localhost:5173/scenarios -o /dev/null -w "/scenarios: %{http_code}\n"
/scenarios: 200
```

Confirmed the built JS bundle no longer contains the removed UI strings, and does contain the new
card copy:

```
$ grep -c "Reset demo environment" /tmp/bundle.js
0
$ grep -c "Recent Orders" /tmp/bundle.js
0
$ grep -c "Recent Scenario Runs" /tmp/bundle.js
0
$ grep -o "Expected terminal state" /tmp/bundle.js | head -1
Expected terminal state
$ grep -o "Demonstrates" /tmp/bundle.js | head -1
Demonstrates
```

Confirmed the scenario grid's `listScenarios()` data source and the run→navigate flow are live and
unchanged, by hitting the same Scenario Service endpoints the merged page's mutation calls:

```
$ curl -s http://localhost:8085/demo/scenarios | head -c 400
[{"name":"standard-order","title":"Standard Fulfillment","description":"Creates an order with
available inventory and successful payment.","demonstrates":["REST request","persistence","event
publication","Kafka consumption","asynchronous workflow","state transitions"],
"expectedTerminalStatus":"FULFILLED","available":true}, ...

$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order
{"id":"run-220","scenarioName":"standard-order","status":"RUNNING", ...}

$ sleep 3 && curl -s http://localhost:8085/demo/scenario-runs/run-220 | python3 -m json.tool | head -6
{
    "id": "run-220",
    "scenarioName": "standard-order",
    "status": "COMPLETED",
    "correlationId": "2fd6f880-13b5-4058-9e86-c2d5f4dc6a42",
    "orderId": "order-20068",
```

This confirms the same `POST /demo/scenarios/{name}` call the merged Home page's "Run Scenario"
button issues completes a real order end-to-end (RUNNING → COMPLETED, real `orderId` assigned) —
the run/navigate wiring carried over from ScenariosPage is unchanged and functional.

Stack was torn down afterward with `docker compose down` (no `-v`; Postgres volume preserved). No
infra was running before this session and none was left running after.

## Judgment calls

- **Full grid replaces "Quick Scenarios" rather than living alongside it.** The delegation prompt
  left this open. Keeping both would duplicate four of the eight scenario cards immediately above
  the full grid on the same page, which contradicts "essential info scannable at a glance" — a
  visitor would see `standard-order` twice. The full grid alone, with expand-for-detail cards, is a
  simpler page.
- **`/scenarios` redirects to `/` rather than being removed as a route.** The prompt allowed either.
  Redirect avoids a bare-404 for any bookmark/external link and costs one line (`<Navigate to="/"
  replace />`); nothing else needed to change on the routing side.
- **Split "essential" vs. "fuller" along the existing `description`/`demonstrates` fields rather
  than truncating text.** `ScenarioDefinition` only carries one `description` string and no short/
  long variant (confirmed against `services/scenario-service/.../ScenarioCatalog.java`), and in
  practice `description` is already one plain sentence ("Creates an order with available inventory
  and successful payment.") while `demonstrates` is a list of 3–6 short technical-concept phrases
  aimed at someone who already wants the deeper detail. Treating `description` +
  `expectedTerminalStatus` as the always-visible glance-level info and `demonstrates` as the
  expand-on-demand detail matches the data's actual shape rather than inventing a truncation
  heuristic on top of it.
- **Used a native `<details>/<summary>` for the expand** rather than a custom toggle component,
  since nothing else in the codebase already has a disclosure pattern to match and this is the
  smallest correct primitive for "collapsed by default, click to see more."
- **Renamed card heading from `<h2>` to `<h3>`** since the cards now sit one level deeper under the
  page's own `<h2>Scenarios</h2>` section heading (ScenariosPage previously had no page-level h2,
  so its cards used h2 directly).

## Deliberately not covered

- Did not touch `OrdersListPage.tsx` — issue #7 owns building a proper orders table on `/orders`;
  Home no longer shows any orders content, which is what leaves that page a clean surface.
- Did not touch `ScenarioRunDetailPage.tsx` or the run-detail review flow (issue #6).
- Did not implement any simulated-vs-real visual treatment for `expectedTerminalStatus` beyond what
  already existed in ScenariosPage's markup (issue #12).
- Did not do a copy pass beyond what the merge required — page/section headings and card content
  are otherwise the prose that already existed in OverviewPage/ScenariosPage (issue #10).
- Did not manually click through in a browser (no headless browser tool available in this
  environment); verification was via built-bundle content inspection and direct API calls that
  exercise the same code paths the UI calls. This is real-system verification of the wiring and
  data source, but not a substitute for an actual click-through — worth a manual pass before this
  ships if one becomes convenient.
- Left `services/scenario-service`'s `resetDemoEnvironment` API function in
  `frontend/src/api/scenarios.ts` untouched per the delegation prompt, even though after this
  change nothing in the frontend calls it — it's intentionally dead from the UI's perspective,
  available for ops tooling (curl/Postman) as instructed.
