# Issue #12 — distinguish "expected demo outcome" from "actual fault"

## What changed

- `frontend/src/components/StatusBadge.tsx` — split the status→class mapping. `REJECTED_OUT_OF_STOCK`
  and `PAYMENT_FAILED` now map to a new `status-expected` class instead of `status-failure`; `FAILED`
  keeps `status-failure`. Added a `title` tooltip per status explaining the distinction, and an
  `ⓘ` marker prefix on the two expected-outcome badges so the difference is visible even without
  hovering. Classification is driven by an `EXPECTED_OUTCOME_STATUSES` set built directly from
  `docs/order-state-machine.md` lines 21-26/119-124, cited in a comment.
- `frontend/src/index.css` — added `--expected`/`--expected-bg` CSS variables (light and dark) and a
  `.status-expected` class (blue/informational, distinct from the existing amber `status-pending` and
  red `status-failure`).
- `frontend/src/pages/OverviewPage.tsx` — two changes:
  1. Replaced the ad hoc `kafka.state === 'UP' ? success : pending` ternaries with a new `infraClass()`
     helper: `UP` → success, `'no data'` → `status-expected` (was `status-pending`/amber before — a
     benign "not reported yet" state, not literally in-progress), anything else (e.g. an actually
     reported `DOWN` component) → `status-failure`.
  2. The scenario grid's "Expected terminal state" line now renders the value as a
     `status status-expected` badge instead of plain `<strong>` text, reusing the same visual language.
- `frontend/src/pages/ScenarioRunDetailPage.tsx` — imported `getOrder` from `../api/orders`. Added a
  second query, gated on `data.orderId` being set **and** the scenario run having reached a terminal
  status (`COMPLETED`/`FAILED`) — fetching mid-run would compare against a not-yet-terminal order
  status and produce a meaningless "mismatch." Rendered the existing "Expected terminal status" line
  as a `status-expected` badge (was plain `<strong>`), and added a new line beneath it: once the order
  is fetched, it shows either "Actual outcome matches expected: …" in `status-success` (green) or
  "Actual outcome differs from expected — order is …" in `status-failure` (red); while the order fetch
  is in flight it shows a neutral "Checking actual order outcome…" hint. The scenario-run's own
  `RUNNING`/`COMPLETED`/`FAILED` pill was left untouched — it is a different concept (did the run
  execute) from the order's business outcome, per the task's explicit instruction not to conflate them.

## How this was verified

Type-checked and built the frontend, then exercised the real stack via `docker compose up --build`.

```
$ cd frontend && npx tsc --noEmit
(no output — clean)

$ npm run build
...
✓ built in 474ms

$ npm run lint
> frontend@0.0.0 lint
> oxlint
(no output — clean)
```

Brought up the full stack (nothing was running beforehand):

```
$ docker compose up --build -d
...
 Container orderfulfillment-postgres Healthy
 Container orderfulfillment-kafka Healthy
 Container orderfulfillment-order-service Healthy
 Container orderfulfillment-inventory-service Healthy
 Container orderfulfillment-payment-service Healthy
 Container orderfulfillment-fulfillment-service Healthy
 Container orderfulfillment-scenario-service Healthy
 Container orderfulfillment-frontend Started
```

Triggered `out-of-stock` for real via the Scenario Service API and followed it to completion:

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/out-of-stock
{"id":"run-224","scenarioName":"out-of-stock","status":"RUNNING", ...}

$ curl -s http://localhost:8085/demo/scenario-runs/run-224
{
  "status": "COMPLETED",
  "orderId": "order-20073",
  "timeline": [
    {"kind":"HTTP","label":"POST /api/orders", ...},
    {"kind":"STATE_CHANGE","label":"Order REJECTED_OUT_OF_STOCK", ...}
  ]
}

$ curl -s http://localhost:8081/api/orders/order-20073
{
  "status": "REJECTED_OUT_OF_STOCK",
  "statusHistory": [
    {"status":"PENDING", ...},
    {"status":"REJECTED_OUT_OF_STOCK", "sourceEventId":"4deabd01-..."}
  ]
}
```

Triggered `payment-failure` the same way:

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/payment-failure
{"id":"run-225","scenarioName":"payment-failure","status":"RUNNING", ...}

$ curl -s http://localhost:8085/demo/scenario-runs/run-225
{
  "status": "COMPLETED",
  "orderId": "order-20074",
  "timeline": [
    ...
    {"kind":"STATE_CHANGE","label":"Order INVENTORY_RESERVED", ...},
    {"kind":"STATE_CHANGE","label":"Order PAYMENT_PENDING", ...},
    {"kind":"STATE_CHANGE","label":"Order PAYMENT_FAILED", ...},
    ...
  ]
}
```

Confirmed `GET /demo/scenarios` reports `expectedTerminalStatus` matching each order's actual final
status exactly, which is what `ScenarioRunDetailPage`'s new match/mismatch logic compares:

```
$ curl -s http://localhost:8085/demo/scenarios | python3 -c "..."
out-of-stock -> REJECTED_OUT_OF_STOCK
payment-failure -> PAYMENT_FAILED
```

For order-20073 (`REJECTED_OUT_OF_STOCK`) and order-20074 (`PAYMENT_FAILED`) this means the page
renders the green "Actual outcome matches expected" state, using the same status-badge classes proven
present in the built bundle below.

Confirmed the actually-shipped bundle inside the running frontend container contains the new
copy/classes (not just my local `dist/`, but what nginx is serving from the `docker compose --build`
image):

```
$ docker compose exec frontend sh -c "grep -o 'status-expected' /usr/share/nginx/html/assets/*.js | head -3"
index-CK1NHTqq.js:status-expected
index-CK1NHTqq.js:status-expected
index-CK1NHTqq.js:status-expected

$ docker compose exec frontend sh -c "grep -o 'Actual outcome' /usr/share/nginx/html/assets/*.js | head -3"
index-CK1NHTqq.js:Actual outcome
index-CK1NHTqq.js:Actual outcome

$ docker compose exec frontend sh -c "grep -o 'Expected business rejection' /usr/share/nginx/html/assets/*.js | head -3"
index-CK1NHTqq.js:Expected business rejection
index-CK1NHTqq.js:Expected business rejection
```

Verified a real fault still reads as alarming and is unaffected by this change: stopped order-service
and confirmed its health check is unreachable (which `fetchServiceHealth`/`stateClass` — untouched by
this ticket — already maps to `status-failure`/red, not the new `status-expected`):

```
$ docker compose stop order-service
 Container orderfulfillment-order-service Stopped

$ curl -s -o /dev/null -w "order-service http status: %{http_code}, curl exit: %{exitcode}\n" http://localhost:8081/actuator/health
order-service http status: 000, curl exit: 7
```

Restarted order-service and confirmed it came back healthy, then tore the stack down (it was not
running before this task started):

```
$ docker compose start order-service
$ curl -s -o /dev/null -w "order-service http status after restart: %{http_code}\n" http://localhost:8081/actuator/health
order-service http status after restart: 200

$ docker compose down
 Network kafka-portfolio-project_default Removed
```

No `-v` flag used — volumes were left intact.

## Judgment calls

- **Visual language for "expected outcome": blue/informational, not amber.** `status-pending` (amber)
  already means "in progress," and reusing it for a terminal, designed outcome would be misleading —
  a viewer might wait for it to resolve into something else. Introduced a new `--expected`/`status-expected`
  color (blue) instead of overloading either existing hue.
  - Rejected alternative: keep the badge on `status-failure` (red) but soften the copy. Kept looking
    alarming at a glance regardless of copy, which is exactly the ambiguity this ticket exists to
    remove.
- **Added an `ⓘ` marker plus a `title` tooltip on `StatusBadge`, not just a color change.** Color alone
  is not scannable for colorblind viewers and doesn't work in the CI/text sense at all; the marker and
  tooltip make the distinction available two ways beyond hue. Kept it to a small prefix rather than a
  wordier badge string so table density (`OrdersListPage`) isn't disrupted.
- **`infraClass()` for Kafka/PostgreSQL rows treats a real reported `DOWN` component as `status-failure`,
  not `status-pending` as the old code did.** The old ternary only special-cased `UP`; anything else,
  including a genuinely reported failure, fell into `status-pending` (amber) — under-representing a real
  problem. This was a pre-existing gap directly adjacent to the "no data" ambiguity the ticket calls out,
  and fixing the three-way split (healthy / benign-absence / real-failure) together was the natural
  shape of the fix rather than a separate follow-up. Could not exercise the real-`DOWN`-component branch
  end-to-end because none of the actuator health endpoints in this stack expose `components` by default
  (no `management.endpoint.health.show-components` set) — see Deliberately not covered.
- **Gated the order-outcome fetch on `TERMINAL_RUN_STATUSES.has(data.status)`, not just `data.orderId`
  being set.** Fetching and comparing while the run (and therefore the order) is still mid-flight would
  produce a false "mismatch" every time, since the order hasn't reached its terminal status yet. Waiting
  for run-terminal avoids that noise; the "Checking actual order outcome…" hint covers the brief window
  after the run finishes but before the order fetch resolves.
- **Match state renders in `status-success` (green), not `status-expected` (blue).** These are two
  different claims: `status-expected` on the static "Expected terminal status" line says "this scenario
  is *designed* to end here," which is known before the run happens. The match indicator is a
  *confirmation* that the real system actually did what it was designed to do — a stronger, positive
  claim — so it gets the same green already used for `FULFILLED`/happy-path success elsewhere in the
  app, rather than reusing blue for a different kind of statement.
- **Did not touch the scenario-run status pill's own `COMPLETED`/`FAILED`/`RUNNING` logic.** The task
  was explicit that this is a distinct concept from the order's business outcome and warned against
  conflating them; left it exactly as-is and added the order-outcome comparison as a separate line.

## Deliberately not covered

- Could not exercise the `infraClass()` real-`DOWN`-component branch against a live response, because
  no service in this stack currently returns Actuator `components` in its `/actuator/health` body (all
  return the bare `{"status":"UP","groups":[...]}` shape without `show-components` enabled) — confirmed
  via `curl http://localhost:8081/actuator/health`. The `UP` and `'no data'` branches were exercised for
  real (`UP` rows visible during the live `docker compose` run; `'no data'` is what Kafka/PostgreSQL
  rows show under current Actuator config). The third branch is covered by code review and the
  pre-existing pattern (`stateClass` in the same file already maps `DOWN`/`UNREACHABLE` to
  `status-failure` for the named-service rows) but not by a live component-level `DOWN` response.
  Enabling `show-components` to close this gap is backend Actuator configuration, out of this
  frontend-only ticket's scope.
- Did not visually screenshot the running app in a browser — no browser/preview tool was available in
  this environment. Verification instead confirmed (a) the API responses driving the pages are exactly
  what the new component logic expects (order status matching `expectedTerminalStatus` for both
  triggered scenarios), and (b) the actual strings/CSS classes are present in the bundle being served
  by the running `docker compose` frontend container, not just in a local build artifact.
- Did not run or trigger `standard-order`, `duplicate-event`, `consumer-outage`, `poison-message`,
  `inventory-contention`, or `high-volume` scenarios — out of the exit criteria's explicit list
  (`out-of-stock` and `payment-failure`), and `FAILED`'s red treatment was already covered by the
  pre-existing `status-failure` class (unchanged by this ticket) rather than new code needing fresh
  verification.
- Did not add a distinct icon/treatment differentiating `status-pending`'s "checking…" sub-state from
  its "in-progress, will resolve to success or failure" sub-state on `OverviewPage` — out of scope per
  the ticket's explicit exclusion of issue #11 (general loading-state indicators).
