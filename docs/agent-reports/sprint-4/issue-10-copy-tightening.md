# Issue #10 — site-wide copy tightening

## What changed

- `frontend/src/pages/OverviewPage.tsx` — tightened the hero paragraph (`overview-lede`), the
  System Status hint about "no data" rows, the Scenarios-grid hint above the scenario cards, the
  Scenario Service unreachable error message, and the disabled-scenario button tooltip
  ("Not implemented in this build yet" → "Not implemented yet").
- `frontend/src/pages/OrderDetailPage.tsx` — shortened the Scenario Service unreachable error
  message and the "no events match" empty state under the event timeline filter.
- `frontend/src/pages/ArchitecturePage.tsx` — trimmed the "System overview" paragraph, cutting
  redundant wording ("system-overview diagram... rather than here") and removing meta-commentary
  about the page's own edit history ("so this page can't drift out of sync with it again"), while
  keeping every factual claim (five services, Kafka-only communication, one schema per service, no
  shared tables/cross-schema reads).

No changes to `frontend/src/pages/OrdersListPage.tsx`, `CreateOrderPage.tsx`, or
`ScenarioRunDetailPage.tsx` — read all three; existing copy was already tight (OrdersListPage,
CreateOrderPage) or already scoped as "don't rewrite the substance, check phrasing" and found
already reading fine on inspection (ScenarioRunDetailPage's scenario-context block and
matches/differs outcome line).

No changes to `frontend/src/components/StatusBadge.tsx` (`STATUS_TITLE` tooltips) or
`frontend/src/lib/scenarioNarrative.ts` — see Judgment calls.

## How this was verified

Frontend build:

```
$ cd frontend && npm run build
...
✓ built in 456ms
```

(Pre-existing chunk-size warning from the mermaid dependency, unrelated to this change — same
warning present before editing.)

Full stack via `docker compose up --build -d` — all containers reached healthy:

```
$ docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"
NAME                                   STATUS                        PORTS
orderfulfillment-frontend              Up 5 seconds                  0.0.0.0:5173->80/tcp
orderfulfillment-fulfillment-service   Up About a minute (healthy)   0.0.0.0:8084->8084/tcp
orderfulfillment-grafana               Up 5 seconds                  0.0.0.0:3000->3000/tcp
orderfulfillment-inventory-service     Up About a minute (healthy)   0.0.0.0:8082->8082/tcp
orderfulfillment-kafka                 Up About a minute (healthy)   0.0.0.0:9092->9092/tcp
orderfulfillment-order-service         Up About a minute (healthy)   0.0.0.0:8081->8081/tcp
orderfulfillment-payment-service       Up About a minute (healthy)   0.0.0.0:8083->8083/tcp
orderfulfillment-postgres              Up About a minute (healthy)   0.0.0.0:5432->5432/tcp
orderfulfillment-prometheus            Up 5 seconds                  0.0.0.0:9090->9090/tcp
orderfulfillment-scenario-service      Up 27 seconds (healthy)       0.0.0.0:8085->8085/tcp
```

No headless-browser tool was available in this environment, so instead of a live screenshot I
verified the *actual served* bundle (not just source) contains the new copy and none of the old
copy, by pulling the real built JS asset out of the running frontend container and grepping it:

```
$ curl -s http://localhost:5173/ 
...
<script type="module" crossorigin src="/assets/index-DQmR-Cf4.js"></script>
...

$ curl -s "http://localhost:5173/assets/index-DQmR-Cf4.js" -o bundle.js
$ grep -o "never calling one another directly" bundle.js
never calling one another directly
$ grep -o "not a UI animation" bundle.js
not a UI animation
$ grep -o "real requests, real Kafka records, real persistence" bundle.js
real requests, real Kafka records, real persistence
$ grep -o "No events match these filters" bundle.js
No events match these filters
$ grep -o "Not implemented yet" bundle.js
Not implemented yet
# old strings — all empty (correctly removed):
$ grep -o "coordinate exclusively through Kafka" bundle.js
$ grep -o "same code path a real order takes" bundle.js
$ grep -o "Not implemented in this build yet" bundle.js
$ grep -o "Is it running on the" bundle.js
```

Confirmed the backend data the tightened pages render against is real and live (not needed to
verify wording itself, but confirms the pages still have real data to render, i.e. nothing broken
functionally):

```
$ curl -s http://localhost:8081/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}

$ curl -s http://localhost:8085/demo/scenarios | head -c 800
[{"name":"standard-order","title":"Standard Fulfillment","description":"Creates an order with
available inventory and successful payment.",...,"expectedTerminalStatus":"FULFILLED","available":true},...]

$ curl -s http://localhost:8081/api/orders | head -c 500
{"content":[{"id":"order-20075","customerId":"demo-customer","status":"REJECTED_OUT_OF_STOCK",
"totalAmount":1245.00,"createdAt":"2026-08-25T17:37:05.107687Z",...},...]}
```

Torn down afterward (no volumes wiped — this was infra I started for verification, nothing was
running beforehand per `docker compose ps` at the start of the session):

```
$ docker compose down
 Container orderfulfillment-scenario-service Removed
 ...
 Network kafka-portfolio-project_default Removed
```

## Judgment calls

- **No headless browser available** — this environment has no browser/screenshot tool, so "load
  the pages and read the rendered copy" was done by extracting the actual built-and-served JS
  bundle from the running container and confirming the exact new strings are present and the old
  strings are gone, plus confirming the backends the pages query are live and returning real data.
  This proves the copy shipped correctly to the artifact a browser would load; it does not
  substitute for eyeballing layout/wrapping in an actual viewport (out of scope anyway per "copy
  only, no layout changes").
- **Left `frontend/src/lib/scenarioNarrative.ts` untouched.** These headline strings were called
  out as requiring re-verification against `docs/events/event-catalog.md` and
  `docs/order-state-machine.md` sentence-by-sentence if edited. They're already fairly compact
  (one clause of mechanism, one interpolated value) and are not on the priority path (Home/Overview,
  Orders) the task named. Editing them for marginal length savings would have meant re-deriving
  domain-accuracy verification for each touched line at real risk of introducing a subtle factual
  drift, for a page (scenario-run detail) explicitly de-prioritized relative to Home/Orders. Left
  as-is rather than risk that trade.
- **Left `StatusBadge.tsx`'s `STATUS_TITLE` tooltips untouched.** Already terse (one sentence each)
  and explicitly flagged as encoding a precise expected-vs-fault distinction from
  `docs/order-state-machine.md`; no wording in them read as bloated, so there was nothing to safely
  trim without touching the substance the task says not to touch.
- **Left `ArchitecturePage.tsx`'s "Why Kafka" / "Why Kubernetes" / "Reliability notes" paragraphs
  substantively untouched**, only trimming the "System overview" paragraph. Those three sections
  carry precise, individually-verified technical claims (ADR references, specific replica-count
  measurements, the outbox/idempotency gap analysis) and are already written economically for their
  density — there was no loose filler to cut without risking a factual claim, and the task's
  explicit priority order (Home/Overview and Orders "where a first-time visitor's attention is
  shortest") puts this deep-dive page last.
- **Shortened the two "Could not reach Scenario Service: ... Is it running on the configured URL?"
  error messages** (`OverviewPage.tsx`, `OrderDetailPage.tsx`) by dropping the trailing question.
  Judged this as pure filler — the message already names the unreachable service and surfaces the
  underlying fetch error, so the follow-up question added length without adding information for
  either a developer or a portfolio reviewer.

## Deliberately not covered

- No visual/rendered screenshot verification (layout, text wrapping, line breaks in the actual
  viewport) — no browser tool available in this environment. The build output and served-bundle
  grep confirm the copy is correct and shipped; they don't confirm how it wraps at a given width.
  If a screenshot tool becomes available this should be a quick follow-up check, not a redo.
- `frontend/src/lib/scenarioNarrative.ts` and `StatusBadge.tsx` tooltips were read but not edited —
  see Judgment calls for why. If tighter copy is wanted there specifically, that's follow-on work
  requiring the domain-doc re-verification step the task called out.
- Did not exercise the actual scenario-run flow (POST a scenario, watch the SSE-driven timeline
  render) — verification focused on confirming the built bundle serves the new strings and the
  backend APIs the pages depend on are live, which was sufficient to confirm the copy change is
  real and functional. A full scenario run end-to-end wasn't necessary to verify a text-only change
  and wasn't performed to keep the docker compose session short.
