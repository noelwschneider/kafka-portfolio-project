# Scenario run detail page — narrative timeline (issue #6)

## What changed

- `frontend/src/lib/scenarioNarrative.ts` (new file) — exports `narrateTimelineEntry(entry: ScenarioTimelineEntry): { headline: string }`. Maps every HTTP/EVENT/STATE_CHANGE label the scenario-service backend actually produces to a plain-language headline sourced from `docs/events/event-catalog.md` §3 (event mechanics) and `docs/order-state-machine.md` (status meanings), interpolating only values actually present on the entry (`detail.statusCode`, `detail.orderId`, the dynamic `listenerId` embedded in pause/resume HTTP labels, `detail.consumer`). Any label outside the known vocabulary falls back to a generic "System recorded …" sentence rather than guessing.
- `frontend/src/pages/ScenarioRunDetailPage.tsx` — three changes:
  1. Imports `listScenarios` and fetches it via `useQuery({ queryKey: ['scenarios'], queryFn: listScenarios })` (same pattern as `OverviewPage.tsx`), finds the definition matching `data.scenarioName`, and renders its `title` (as the card heading), `description`, `demonstrates` list, and `expectedTerminalStatus` in a new `.scenario-context` block inside the existing summary card — framing the run against what it was meant to prove, without attempting to validate the outcome (left to issue #12).
  2. `TimelineEntryDetail` now renders `narrateTimelineEntry(entry).headline` as the primary visible text (`.timeline-headline`), with the raw `kind`/`label` demoted into a smaller, lower-opacity `.timeline-raw` row underneath. The click-to-expand `KNOWN_DETAIL_FIELDS` dump is untouched — same trigger, same content, still reachable exactly as before.
  3. Added a staged-reveal mechanism: `revealedCount` state plus a `setTimeout` chain (`REVEAL_STEP_MS = 180`, `REVEAL_STAGGER_CAP = 12`) that advances one entry at a time up to the cap, then reveals the remainder together, driven off `data.timeline.length` growing (covers both a live SSE-driven timeline and a run that is already `COMPLETED` on first fetch — the effect fires from 0 on mount and re-fires whenever the entry count increases). `revealedCount` resets to 0 on `runId` change. Each `<li>` gets `timeline-reveal` (always) plus `timeline-revealed` once its index is under `revealedCount`; the fade/slide is pure CSS transition.
- `frontend/src/index.css` — added a new block (search for the comment `/* Scenario run detail page: staged reveal + narrative headline as the primary text. */`) with these **new** class names: `.timeline-reveal`, `.timeline-revealed`, `.timeline-main`, `.timeline-headline`, `.timeline-raw`, `.scenario-context`, `.scenario-context-description`, `.scenario-context-demonstrates`, `.scenario-context-expected`. No existing shared rule (`.timeline`, `.timeline-entry`, `.timeline-row`, `.timeline-time`, `.timeline-kind`, `.timeline-label`, `.timeline-expand`, `.timeline-detail`, `.timeline-detail dt`/`dd`, `.timeline-detail-row`) was modified or removed. `.timeline-kind` and `.timeline-label` are **reused** (not redefined) inside the new `.timeline-raw` wrapper, but only in `ScenarioRunDetailPage.tsx`'s own JSX — `OrderDetailPage.tsx` was not edited and its JSX still nests those two classes directly under `.timeline-row` exactly as before, so its rendering is unaffected. (Note: `frontend/src/index.css` and several other frontend files already carried unrelated, pre-existing uncommitted changes — e.g. `.scenario-card-details`, `.modal`, `.orders-table` — from work in progress before this task started; those are not part of this change and were left as-is.)

`services/scenario-service/src/main/java/...` and `frontend/src/pages/OrderDetailPage.tsx` were not touched, per constraints.

## How this was verified

1. Type-checked build:

```
$ cd frontend && npm run build
...
✓ built in 526ms
```

(re-ran after removing a temporary verification script — same result, `✓ built in 542ms`, no type errors.)

2. Brought up the full stack (nothing was running beforehand — confirmed with `docker compose ps` returning no containers):

```
$ docker compose up --build -d
...
 Container orderfulfillment-scenario-service Healthy
 Container orderfulfillment-frontend Started
 Container orderfulfillment-prometheus Started
 Container orderfulfillment-grafana Started
```

All services reported `Healthy` before scenario-service and frontend started.

3. Triggered real scenario runs against the live scenario-service and fetched the resulting timeline JSON:

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order
{"id":"run-221","scenarioName":"standard-order","status":"RUNNING",...}

$ curl -s http://localhost:8085/demo/scenario-runs/run-221 | python3 -m json.tool
{
  "id": "run-221", "status": "COMPLETED", "orderId": "order-20070", ...
  "timeline": [
    {"sequence":1,"kind":"HTTP","label":"PUT /demo/payment-behavior","detail":{"statusCode":200}},
    {"sequence":2,"kind":"HTTP","label":"POST /api/orders","detail":{"orderId":"order-20070","statusCode":201}},
    {"sequence":3,"kind":"STATE_CHANGE","label":"Order REJECTED_OUT_OF_STOCK","detail":{"status":"REJECTED_OUT_OF_STOCK","orderId":"order-20070"}}
  ]
}
```

(First run landed on `REJECTED_OUT_OF_STOCK` — demo inventory was already low from prior sessions' data in the reused Postgres volume. Ran `/demo/reset` and a second `standard-order` run to also exercise the successful path:)

```
$ curl -s -X POST http://localhost:8085/demo/reset
{"inventoryRestored":true,"consumersResumed":[],"paymentBehaviorCleared":true,"resetAt":"..."}

$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order | ...  # run-222
$ curl -s http://localhost:8085/demo/scenario-runs/run-222 | python3 -m json.tool
{
  "id": "run-222", "status": "COMPLETED", "orderId": "order-20071",
  "timeline": [
    {"kind":"HTTP","label":"PUT /demo/payment-behavior", ...},
    {"kind":"HTTP","label":"POST /api/orders", "detail":{"orderId":"order-20071","statusCode":201}},
    {"kind":"STATE_CHANGE","label":"Order INVENTORY_RESERVED", ...},
    {"kind":"STATE_CHANGE","label":"Order PAYMENT_PENDING", ...},
    {"kind":"STATE_CHANGE","label":"Order PAID", ...},
    {"kind":"STATE_CHANGE","label":"Order FULFILLMENT_PENDING", ...},
    {"kind":"STATE_CHANGE","label":"Order FULFILLED", ...}
  ]
}
```

4. Fed both real payloads plus every remaining label in the documented vocabulary (all 8 EVENT types in both `published`/`consumed` phases, the remaining HTTP labels including dynamic `listenerId` pause/resume, and the remaining STATE_CHANGE statuses plus `High-volume batch summary`) through `narrateTimelineEntry` directly via `npx tsx`, confirming every combination hits a specific branch rather than the fallback:

```
$ npx tsx src/lib/__verify.ts   # temporary script, removed after
HTTP | PUT /demo/payment-behavior => Scenario configured Payment Service to simulate a specific outcome on the next authorization
HTTP | POST /api/orders => Scenario submitted a new order to Order Service (HTTP 201)
STATE_CHANGE | Order REJECTED_OUT_OF_STOCK => Order order-20070 reached REJECTED_OUT_OF_STOCK: The order was rejected because inventory could not cover every line
STATE_CHANGE | Order INVENTORY_RESERVED => Order order-20071 reached INVENTORY_RESERVED: Inventory was reserved for every line, and the order is ready for payment
STATE_CHANGE | Order PAYMENT_PENDING => Order order-20071 reached PAYMENT_PENDING: The order is awaiting payment authorization
STATE_CHANGE | Order PAID => Order order-20071 reached PAID: Payment was authorized for the order
STATE_CHANGE | Order FULFILLMENT_PENDING => Order order-20071 reached FULFILLMENT_PENDING: The order is awaiting shipment creation
STATE_CHANGE | Order FULFILLED => Order order-20071 reached FULFILLED: A shipment was created and the order has reached its final, successful state
---full vocab---
HTTP | DELETE /demo/payment-behavior => Scenario cleared the simulated payment behavior, restoring normal authorization
HTTP | POST /demo/consumers/inventory-events-consumer/pause => Scenario paused the `inventory-events-consumer` Kafka consumer to simulate an outage
HTTP | POST /demo/consumers/inventory-events-consumer/resume => Scenario resumed the `inventory-events-consumer` Kafka consumer, ending the simulated outage
HTTP | Burst order submission complete => Scenario finished submitting a burst of concurrent orders
EVENT | OrderCreated => Order Service persisted the order as PENDING and published `OrderCreated` to Kafka for Inventory Service to react to
EVENT | InventoryReserved => order-service consumed `InventoryReserved` from Kafka — Inventory Service reserved every line against stock in one transaction and published `InventoryReserved` so Order Service can advance the order
EVENT | InventoryReservationFailed => Inventory Service could not satisfy at least one line — a business rejection, not an error — and published `InventoryReservationFailed`
EVENT | InventoryReleased => Inventory Service compensated by releasing the earlier reservation after a payment rejection, publishing `InventoryReleased`
EVENT | PaymentRequested => Order Service asked Payment Service to authorize the charge, carrying an idempotency key, via `PaymentRequested`
EVENT | PaymentAuthorized => fulfillment-service consumed `PaymentAuthorized` from Kafka — Payment Service authorized the charge and published `PaymentAuthorized` — this project's one fan-out event, consumed independently by both Order Service and Fulfillment Service
EVENT | PaymentRejected => The payment simulator declined the charge — a non-retryable business outcome — triggering Inventory's compensating release via `PaymentRejected`
EVENT | ShipmentCreated => Fulfillment Service created a shipment after consuming `PaymentAuthorized`, publishing `ShipmentCreated` as the order reaches FULFILLED
STATE_CHANGE | Order PENDING => Order reached PENDING: The order was created and is awaiting inventory reservation
STATE_CHANGE | Order PAYMENT_FAILED => Order reached PAYMENT_FAILED: Payment was declined for the order, and inventory is being released
STATE_CHANGE | Order FAILED => Order reached FAILED: The order reached a terminal failure state
STATE_CHANGE | High-volume batch summary => Scenario recorded an aggregate summary across the whole batch of orders, not a single order's state
```

5. Confirmed `listScenarios()` (`GET /demo/scenarios`) returns the `title`/`description`/`demonstrates`/`expectedTerminalStatus` fields the page now consumes:

```
$ curl -s http://localhost:8085/demo/scenarios | python3 -m json.tool | head -20
[
    {
        "name": "standard-order",
        "title": "Standard Fulfillment",
        "description": "Creates an order with available inventory and successful payment.",
        "demonstrates": ["REST request","persistence","event publication","Kafka consumption","asynchronous workflow","state transitions"],
        "expectedTerminalStatus": "FULFILLED",
        "available": true
    },
    ...
]
```

6. Confirmed the frontend container actually served the rebuilt bundle (not a stale image) by grepping the compiled JS served from the running container for narrative text and the new CSS class:

```
$ docker exec orderfulfillment-frontend sh -c "grep -l 'timeline-headline' /usr/share/nginx/html/assets/*.js"
/usr/share/nginx/html/assets/index-COrC2BGO.js

$ curl -s http://localhost:5173/assets/index-COrC2BGO.js | grep -o "Order Service persisted the order as PENDING"
Order Service persisted the order as PENDING

$ curl -s http://localhost:5173/assets/index-COrC2BGO.js | grep -o "scenario-context-demonstrates"
scenario-context-demonstrates
```

7. Tore down everything this session started:

```
$ docker compose down
 Container orderfulfillment-scenario-service Removed
 ...
 Network kafka-portfolio-project_default Removed
```

No `-v`, so volumes (including the already-existing demo inventory data) were preserved as found.

## Judgment calls

- **No browser automation available in this environment** — verification of visual rendering (fade-in stagger, headline as primary text, secondary raw label styling) was done by (a) confirming the compiled bundle served by the running container contains the exact narrative strings and new class names, and (b) exercising `narrateTimelineEntry` directly against real backend payloads plus the full documented label vocabulary. I did not load the page in an actual browser viewport to eyeball the CSS transition. This is the one piece of "loading the page" verification the prompt allowed substituting with the curl+mapping-confirmation path when browser inspection isn't available.
- **EVENT-kind entries never appeared in the two real runs I triggered** — both `standard-order` runs produced only `HTTP` and `STATE_CHANGE` entries; no `EVENT` entries were present in the live timeline JSON despite the OpenAPI contract and the task background describing them. I did not chase this — it's a scenario-service backend question, out of scope for a frontend-only task and not something to route around locally. My narration function fully covers all 8 event types per the given vocabulary regardless, verified via direct function calls (step 4 above) since I couldn't get the backend to actually emit one in this session.
- **Reveal stagger tuning** — chose `180ms` per step (within the requested 150-250ms range) and capped individual stagger at 12 entries, after which the remainder reveals together on the next tick. Not specified precisely in the prompt; picked round numbers that keep even the longest realistic timeline (~10-15 entries for the more elaborate scenarios) under ~2.5s total reveal time.
- **Scenario title override** — used `scenarioDefinition?.title ?? data.scenarioName` as the card `<h2>`, falling back to the raw `scenarioName` if `listScenarios()` hasn't resolved yet or doesn't contain a match (e.g. a scenario removed from the catalog after a run for it already exists). This keeps the page from showing a blank heading during the brief window before the scenarios query resolves.
- **`revealedCount` reset on `runId` change** — added an effect not explicitly requested but necessary: without it, navigating from one run's detail page to another (client-side route change, same mounted component) would carry over a stale `revealedCount` and skip the stagger for the new run's timeline.
- Reused `.timeline-kind` and `.timeline-label` inside the new `.timeline-raw` wrapper rather than inventing yet more classes, since the prompt explicitly permits changing what renders inside `ScenarioRunDetailPage.tsx`'s own JSX as long as the shared CSS *rules* for those classes aren't altered — I left the CSS rules for both fully untouched.

## Deliberately not covered

- No visual/browser-level check of the fade-slide animation itself (see Judgment calls above) — only that the CSS and class-toggling logic are present and wired correctly in the served bundle.
- Did not attempt to trigger the `high-volume`, `consumer-outage`, `poison-message`, `inventory-contention`, or `duplicate-event` scenarios live — verification of their specific labels (`Burst order submission complete`, `High-volume batch summary`, consumer pause/resume) relied on the direct-function-call check against the documented vocabulary rather than a live run, since triggering all eight scenarios was not necessary to prove the mapping is complete and correct.
- Did not investigate why the live backend never emitted `EVENT`-kind timeline entries in the two runs observed — flagged above as an open question, not a frontend concern, and not something this task's scope covers.
- Issue #12's job (validating a run's actual terminal status against `expectedTerminalStatus`) is explicitly not implemented here — the page states the expected terminal status as context only, per the delegation prompt's own scope boundary.
- Pre-existing uncommitted changes to other frontend files (`App.tsx`, `ArchitecturePage.tsx`, `CreateOrderPage.tsx`, `OrderDetailPage.tsx`, `OrdersListPage.tsx`, `OverviewPage.tsx`, deletions of `EventExplorerPage.tsx`/`ScenariosPage.tsx`/`SystemHealthPage.tsx`, and unrelated `index.css` blocks) were present in the working tree before this task started and are untouched by this change — noted here only so they aren't mistaken for part of this diff.
