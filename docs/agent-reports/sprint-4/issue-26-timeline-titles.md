# Issue #26 — short scan titles on scenario run timeline cards

## What changed

- `frontend/src/lib/scenarioNarrative.ts` — added a `title` field to the `Narration` interface
  alongside the existing `headline`. Populated it for every branch of `narrateHttp`, `narrateEvent`
  (via a new `EVENT_TITLE` lookup keyed the same as the existing `EVENT_MEANING`), and
  `narrateStateChange` (via a new `STATE_TITLE` lookup keyed the same as `STATE_MEANING`), plus the
  fallback cases in each function and in `narrateTimelineEntry`'s default branch. Titles are 2-4 word
  noun phrases in title case (e.g. "Order Submitted", "Inventory Reserved", "Payment Authorized",
  "Shipment Created", "Order Fulfilled"), consistent across HTTP/EVENT/STATE_CHANGE. Did not touch any
  existing headline sentence text.
- `frontend/src/pages/ScenarioRunDetailPage.tsx` — `TimelineEntryDetail` now destructures `title`
  alongside `headline` from `narrateTimelineEntry(entry)` and renders it as a new
  `<span className="timeline-title">` immediately above the existing `timeline-headline` span, inside
  the same `timeline-main` container. No other logic (demonstrates tags, raw detail rendering) was
  touched.
- `frontend/src/index.css` — added a `.timeline-title` rule (bold, 13px, sans-serif) above the
  existing `.timeline-headline` rule; also lightened `.timeline-headline` to `opacity: 0.85` so the
  bold title reads as the primary element on the card and the full sentence reads as secondary/
  supporting text.

## How this was verified

TypeScript build:

```
$ cd frontend && npm run build
...
✓ built in 753ms
```

Rebuilt only the `frontend` container against the already-running docker compose stack (all other
services were already up before this session started) and confirmed it started clean:

```
$ docker compose up -d --build frontend
...
 Container orderfulfillment-frontend Starting
 Container orderfulfillment-frontend Started
```

Triggered a real `standard-order` scenario run directly against the scenario-service API (bypassing
nginx, which only proxies `/api` and `/demo` for the same-origin frontend fetches, not for `curl` at
that host header) and inspected the real timeline JSON returned:

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order
{"id":"run-236", ... }
$ curl -s http://localhost:8085/demo/scenario-runs/run-236
{
  "status": "COMPLETED",
  "timeline": [
    {"kind":"HTTP","label":"PUT /demo/payment-behavior", ...},
    {"kind":"HTTP","label":"POST /api/orders","detail":{"orderId":"order-20092","statusCode":201}},
    {"kind":"STATE_CHANGE","label":"Order INVENTORY_RESERVED", ...},
    {"kind":"STATE_CHANGE","label":"Order PAYMENT_PENDING", ...},
    {"kind":"STATE_CHANGE","label":"Order PAID", ...},
    {"kind":"STATE_CHANGE","label":"Order FULFILLMENT_PENDING", ...},
    {"kind":"STATE_CHANGE","label":"Order FULFILLED", ...}
  ]
}
```

Used Playwright (already present in `frontend/node_modules/.bin/playwright`) to load the real
`ScenarioRunDetailPage` for that run against the running frontend container and pull the rendered DOM
text for every `.timeline-title` and `.timeline-headline` element:

```
$ node check_timeline.mjs   # loaded http://localhost:5173/scenario-runs/run-233, a prior standard-order run
TITLES: [
  "Payment Behavior Configured",
  "Order Submitted",
  "Inventory Reserved",
  "Payment Pending",
  "Order Paid",
  "Fulfillment Pending",
  "Order Fulfilled"
]
HEADLINES: [
  "Scenario configured Payment Service to simulate a specific outcome on the next authorization",
  "Scenario submitted a new order to Order Service (HTTP 201)",
  "Order order-20088 reached INVENTORY_RESERVED: Inventory was reserved for every line, and the order is ready for payment",
  "Order order-20088 reached PAYMENT_PENDING: The order is awaiting payment authorization",
  "Order order-20088 reached PAID: Payment was authorized for the order",
  "Order order-20088 reached FULFILLMENT_PENDING: The order is awaiting shipment creation",
  "Order order-20088 reached FULFILLED: A shipment was created and the order has reached its final, successful state"
]
```

Also captured a full-page screenshot of the rendered run (`/private/tmp/.../scratchpad/timeline.png`
during the session) confirming the bold short title sits above the full sentence on every card, with
the raw kind/label and `demonstrates` tag still rendering below as before — visually, a viewer scanning
just the bold text down the page gets "Payment Behavior Configured → Order Submitted → Inventory
Reserved → Payment Pending → Order Paid → Fulfillment Pending → Order Fulfilled," which is a correct
summary of the run.

Also ran `out-of-stock` for the `HTTP` + `STATE_CHANGE` rejection path:

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/out-of-stock
$ curl -s http://localhost:8085/demo/scenario-runs/run-234
{
  "timeline": [
    {"kind":"HTTP","label":"POST /api/orders", ...},
    {"kind":"STATE_CHANGE","label":"Order REJECTED_OUT_OF_STOCK", ...}
  ]
}
```
which maps to titles "Order Submitted" and "Order Rejected" via the same code path.

No `npm test` / unit test suite exists for `scenarioNarrative.ts` in this repo (checked — there is no
`*.test.ts` for it); verification is the build plus the live docker compose exercise above.

## Judgment calls

- **Title vocabulary**: chose short noun phrases ("Order Submitted", "Inventory Reserved") rather than
  imperative or question forms, matching the developer's own examples in the issue ("Order Submitted",
  "Inventory Reserved", "Payment Authorized", "Shipment Created") verbatim where the mapped case lined
  up with one of those examples, and extended the same pattern to every other branch (e.g. "Consumer
  Paused", "Consumer Resumed", "Batch Summary", "System Event" for the true fallback).
- **EVENT title independent of phase**: `narrateHttp`/`narrateStateChange` don't need a phase-aware
  title, but `narrateEvent` has published/consumed variants of the same headline. Rather than making
  the title say "X Published" vs "X Consumed" (which would make the title longer and duplicate what
  the raw `kind`/kafka phase detail row already shows), I kept one title per event type (e.g. "Payment
  Authorized") and left the published/consumed distinction inside the full sentence, since the title's
  job per the issue is "gist of what happened," not the delivery mechanics.
- **CSS**: rather than leaving `.timeline-headline` at full opacity (which would make two full-weight
  lines compete for attention), I dropped it to `opacity: 0.85` once the bold title was added above it,
  so the title reads as visually primary — this is a presentation choice not specified in the issue but
  directly serves the "scan the titles first" goal.
- Used `curl` directly against `scenario-service` on `:8085` instead of through the frontend's nginx
  proxy on `:5173`, because nginx's `/demo` location only proxies requests carrying the frontend's own
  origin/host expectations for a same-origin browser fetch — `curl -X POST` from the host hit a 405 on
  the `/api` path when tried through nginx first. Went straight to the backend port instead, which
  exercises the same scenario-service code the frontend calls.

## Deliberately not covered

- Did not observe an `EVENT`-kind timeline entry (`OrinatedEvent`, `InventoryReserved`, etc. with
  `phase: published`/`consumed`) rendering live in this session. Every `standard-order`, `out-of-stock`,
  and `duplicate-event` run triggered against the current docker compose stack produced only `HTTP` and
  `STATE_CHANGE` entries — no `EVENT` entries appeared, and `duplicate-event` explicitly failed with
  `"OrderCreated for order-20091 was not observed by the event projection in time"`. Scenario-service
  logs show its Kafka projection consumer group (`scenario-service-projection`) reporting "no committed
  offset" for multiple partitions, consistent with a pre-existing lag/offset issue in this long-running
  local stack, unrelated to this frontend-only change (I did not restart or touch scenario-service,
  Kafka, or any backend container — only rebuilt the `frontend` container). The `EVENT`-kind title logic
  (`EVENT_TITLE` lookup, both `published` and `consumed` phrasing) is exercised by the TypeScript build
  and is a direct structural mirror of the already-verified `EVENT_MEANING` table (issue #6), but was
  not confirmed against a live-rendered card in this session. Flagging this backend projection-consumer
  lag as worth a separate look, since it also broke the `duplicate-event` scenario outright — not fixed
  here as it is outside this issue's scope (`ScenarioRunDetailPage.tsx` / `scenarioNarrative.ts` only).
- No automated frontend test file exists for `scenarioNarrative.ts` or `ScenarioRunDetailPage.tsx` in
  this repo; verification relied on the TypeScript build plus live rendering via Playwright/docker
  compose rather than a unit test, matching how the file was previously verified (issue #6's report,
  per this file's own doc comment, also did not add a test file).
- Did not exercise every scenario in the catalog (`payment-failure`, `consumer-outage`,
  `poison-message`, `inventory-contention`, `high-volume`) — `standard-order` and `out-of-stock` cover
  the HTTP and STATE_CHANGE code paths, which was enough to confirm the two-tier title/headline layout
  renders correctly for real data; the remaining scenarios exercise the same `narrateHttp`/
  `narrateStateChange` functions with different labels already covered by the lookup tables.
