# Issue #7 — Orders page nav/route cleanup

## What changed

- `frontend/src/pages/OrdersListPage.tsx` — rewritten. Table gains clickable/sortable column
  headers (`sort-header`, toggling asc/desc with an arrow indicator, default sort `createdAt`
  desc), a richer empty state with a call-to-action button, and monospace/tabular-numeric styling
  for the id and total columns. The page now owns "New order" as an inline modal (state
  `isCreateOpen`) instead of delegating navigation to a parent route; it accepts
  `initialCreateOpen`/`onCreateClosed` props so a deep link can still pre-open the panel.
- `frontend/src/pages/CreateOrderPage.tsx` — stripped the page-level `<section>`/`page-header`/
  "Back to orders" chrome; it now renders just the form (wrapped in a fragment) so it can be
  dropped into `OrdersListPage`'s modal. Added an explicit "Cancel" button next to "Place order"
  inside `.order-form-actions` since the page no longer has its own back button.
- `frontend/src/pages/OrderDetailPage.tsx` — added an `EventTimelineSection` (and
  `EventTimelineEntry` row component) rendered below "Status history". It calls the same
  `queryEvents` from `frontend/src/api/events.ts` used by the old Event Explorer, with `orderId`
  fixed to the page's order and the remaining filters (event type, correlation id, service, topic,
  dead-lettered) tucked behind a `<details>` disclosure. Reuses the existing `.timeline`/
  `.timeline-entry`/`.timeline-detail` CSS classes already used by `ScenarioRunDetailPage`. The
  existing SSE handler now also invalidates the `['order-events', orderId]` query key so the
  timeline refreshes on live order-status-changed events, not just polling.
- `frontend/src/pages/EventExplorerPage.tsx` — deleted. Nothing else imported it (verified via
  grep before removal).
- `frontend/src/App.tsx` — removed the `EventExplorerPage`/`CreateOrderPage` imports, the
  `{ to: '/events', label: 'Event Explorer' }` nav entry, and the standalone `CreateOrderRoute`.
  `/events` now redirects to `/` (matching how `#5`/`#9` retired their routes). `/orders/new`
  is kept as a route but now renders the same `OrdersListRoute`, passing `initialCreateOpen` when
  the path is `/orders/new` and navigating back to `/orders` (`replace`) when the panel closes, so
  the URL still reflects panel state without a page jump.
- `frontend/src/index.css` — added `.sort-header`/`.sort-arrow`, `.order-id-cell`/
  `.order-total-cell`, `.empty-state`, `.order-form-actions`/`.button-secondary`, and
  `.modal-overlay`/`.modal`/`.modal-header`/`.modal-close`.

## Judgment calls

- **Modal vs. inline panel for New Order**: chose a modal overlay (`.modal-overlay`/`.modal`)
  rather than an inline panel embedded in the page flow. The Orders table can be long, and an
  inline panel pushed above/below the table would either require scrolling to reach it or push the
  table down unpredictably; a centered modal keeps the table's scroll position stable and matches
  the "reduce jarring navigation" intent without introducing new page layout shifts.
- **`/orders/new` kept as a route, not dropped**: kept it deep-linkable (e.g. a support link that
  should land straight on the create flow) by having it render the same `OrdersListRoute` with
  `initialCreateOpen=true`, rather than removing the route entirely. Closing the panel while on
  that path navigates back to `/orders` with `replace` so back-button behavior stays sane and the
  URL doesn't get stuck showing `/orders/new` after the panel is dismissed.
- **Event timeline filter UI scoped down, not dropped**: kept event type, correlation id, service,
  topic, and dead-lettered filters (all of Event Explorer's filters except Order ID, which is now
  fixed by the page) but moved them behind a `<details>`/`<summary>` disclosure rather than
  always-visible form chrome, since on an order detail page the common case is "show me this
  order's events," not "let me filter." Reused the same `.scenario-card-details` styling already
  used for scenario "Details" disclosures on Home rather than inventing new CSS.
- **Sorting done client-side**: `listOrders()` fetches up to 50 orders in one page with no
  server-side sort parameter in the frozen OpenAPI contract, so sorting is done in-memory in
  `OrdersListPage` via `useMemo`. Did not touch `docs/openapi/order-service.yaml` — this doesn't
  need a contract change since sorting a page that's already fully fetched client-side is a
  pure frontend concern.
- **Event timeline entries always dead-lettered-flagged inline** (`badge badge-muted` "DLQ" next to
  the topic) rather than as a separate column like the old table, since the timeline is a list of
  rows, not a table — kept it visually consistent with `ScenarioRunDetailPage`'s timeline rows.

## How this was verified

Build and lint:

```
$ npm run build
...
✓ built in 401ms
(only warning is the pre-existing "chunks larger than 500kB" mermaid/vendor warning, unrelated to this change)

$ npm run lint
> frontend@0.0.0 lint
> oxlint
(no output — clean)
```

Full stack via `docker compose up --build -d` (all containers reached healthy):

```
$ docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"
NAME                                   STATUS                    PORTS
orderfulfillment-frontend              Up 3 seconds              0.0.0.0:5173->80/tcp
orderfulfillment-fulfillment-service   Up 34 seconds (healthy)   0.0.0.0:8084->8084/tcp
orderfulfillment-inventory-service     Up 34 seconds (healthy)   0.0.0.0:8082->8082/tcp
orderfulfillment-kafka                 Up 41 seconds (healthy)   0.0.0.0:9092->9092/tcp
orderfulfillment-order-service         Up 34 seconds (healthy)   0.0.0.0:8081->8081/tcp
orderfulfillment-payment-service       Up 34 seconds (healthy)   0.0.0.0:8083->8083/tcp
orderfulfillment-postgres              Up 41 seconds (healthy)   0.0.0.0:5432->5432/tcp
orderfulfillment-scenario-service      Up 13 seconds (healthy)   0.0.0.0:8085->8085/tcp
```

Created a real order through the real Order Service API (the same request the new modal's
`createOrder` mutation issues):

```
$ curl -s -X POST http://localhost:8081/api/orders -H 'Content-Type: application/json' \
    -d '{"customerId":"demo-customer","items":[{"sku":"SKU-001","quantity":2}]}'
{"id":"order-20069","status":"PENDING","createdAt":"2026-08-25T17:04:13.361175930Z"}
```

Confirmed it lands in the list the redesigned `OrdersListPage` renders:

```
$ curl -s "http://localhost:8081/api/orders" | python3 -m json.tool | head -10
{
    "content": [
        {
            "id": "order-20069",
            "customerId": "demo-customer",
            "status": "REJECTED_OUT_OF_STOCK",
            "totalAmount": 258.0,
            "createdAt": "2026-08-25T17:04:13.361176Z",
            "updatedAt": "2026-08-25T17:04:14.403265Z"
        },
```

Confirmed the events-by-order query the new `EventTimelineSection` calls returns real, non-empty
data for an order with a full lifecycle already in the seeded/previous-session data
(`order-20067`):

```
$ curl -s "http://localhost:8085/demo/events?aggregateId=order-20067&size=50" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['totalElements']); \
        [print(e['eventType'], e['topic']) for e in d['content']]"
5
ShipmentCreated fulfillment.events
PaymentAuthorized payments.events
PaymentRequested orders.events
InventoryReserved inventory.events
OrderCreated orders.events
```

Confirmed SPA routing serves the app shell for the retained/redirected routes and that the built,
served bundle actually contains the new UI (not just the source):

```
$ curl -s http://localhost:5173/orders/order-20067 -o /dev/null -w "%{http_code}\n"
200
$ curl -s http://localhost:5173/events -o /dev/null -w "%{http_code}\n"
200

$ JS=$(curl -s http://localhost:5173/ | grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' | head -1)
$ curl -s "http://localhost:5173/$JS" -o /tmp/bundle.js
$ grep -c "Event Explorer" /tmp/bundle.js
0
$ grep -c "New order" /tmp/bundle.js
1
$ grep -c "No orders yet" /tmp/bundle.js
1
$ grep -c "Place the first order" /tmp/bundle.js
1
$ grep -c "modal-overlay" /tmp/bundle.js
1
$ grep -c "order-events" /tmp/bundle.js
1
```

Tore the stack down afterward (no volumes removed — nothing that was already running before this
session, so no prior state to preserve; `docker compose down` without `-v`):

```
$ docker compose down
...
Network kafka-portfolio-project_default Removed
```

## Deliberately not covered

- No actual browser was driven in this session (no browser/preview tool was available) — the
  claims about visual layout (modal centering, sort-arrow rendering, timeline expand/collapse) are
  backed by build success + bundle content checks + reuse of already-shipped CSS classes
  (`.timeline`, `.status`, `.scenario-card-details`), not a rendered screenshot. Someone doing a
  visual QA pass on this ticket should open `/orders`, click a column header, open "New order",
  and expand an event row in a real browser before sign-off.
- Did not add a loading-state pattern beyond the single `{eventsLoading && <p>Loading events…</p>}`
  line — general loading-state work is issue #11, out of scope here per the delegation prompt.
- Did not touch `StatusBadge` or add a simulated-vs-real visual treatment for order status — that's
  issue #12, explicitly out of scope.
- Did not do a copy pass on the new UI strings ("New order", "Place the first order", etc.) beyond
  writing them to be clear — issue #10 owns sitewide copy.
- Client-side sort only covers the currently-fetched page (`listOrders` requests `size=50`); if the
  order count ever exceeds that, sorting won't reach across pages. `listOrders` already had this
  ceiling before this change (no pagination UI exists yet) — not introduced by this ticket, but
  worth flagging since sorting makes the page-size ceiling slightly more visible to a user.
- Did not exercise the DLQ ("Dead-lettered only") filter against a real dead-lettered event, since
  no scenario was run in this session to produce one — the filter wires the same query parameter
  Event Explorer already used and verified, so this is inherited coverage rather than a new gap,
  but it wasn't independently re-checked here.
