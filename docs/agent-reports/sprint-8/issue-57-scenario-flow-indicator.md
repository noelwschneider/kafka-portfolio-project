# Issue #57 — color-coded scenario-run flow indicator

Branch `feature/scenario-flow-indicator`, based off `origin/frontend/interactive-theme-session`.
PR: https://github.com/noelwschneider/kafka-portfolio-project/pull/65 (base:
`frontend/interactive-theme-session`, not merged).

## What changed

- `frontend/src/lib/scenarioFlow.ts` (new) — attribution logic, no JSX. `attributeService(entry)`
  returns the one `ServiceKey` (`order`/`payment`/`inventory`/`fulfillment`) an entry genuinely
  occurred in, or `null`. For `EVENT` entries it reads `detail.producer` (stripping the `-service`
  suffix); for `HTTP` entries it pattern-matches `entry.label`'s method+path against the small,
  closed set of endpoints every scenario actually calls; `STATE_CHANGE` entries always return `null`.
  `flowRoutingLabel(entry)` returns `detail.topic` for EVENT entries or `entry.label` for HTTP
  entries — the value shown on the connector arrow.
- `frontend/src/components/ServiceIcon.tsx` (new) — four plain inline SVG icons (cart/order,
  dollar-in-circle/payment, stacked boxes/inventory, truck/fulfillment), colored via `currentColor`
  so the CSS controls the actual hue. No icon library added.
- `frontend/src/pages/ScenarioRunDetailPage.tsx` — `TimelineEntryDetail` now takes a `service` prop
  and renders a `.timeline-service-badge` before the timestamp on every row (hidden via
  `visibility: hidden` when there's no attribution, so unattributed rows keep their layout). A new
  `TimelineFlowConnector` component renders as its own `<li>` between two timeline rows whenever the
  attributed service actually changes (walking the array and skipping over unattributed entries in
  between, so a `STATE_CHANGE` row in the middle doesn't count as a "break" in the chain). The
  existing `KNOWN_DETAIL_FIELDS` detail rows are untouched — the badge/connector layer sits above
  them, as the issue specified.
- `frontend/src/index.css` — `.timeline-service-badge` (+ one `.service-<key>` rule per service,
  each pulling color/border/background from the matching `--color-service-*` token via
  `color-mix`) and `.timeline-connector`/`.timeline-connector-arrow`/`.timeline-connector-label`.
  New rules use the plain bare-pixel spacing already in use throughout `index.css` — the
  `--space-*` scale that `STYLE_GUIDE.md` describes is still only a placeholder on this branch
  (grepped for it; not yet defined in `:root`), so introducing undefined custom properties myself
  would have been worse than following the file's current, real convention.

## How this was verified

`tsc -b --noEmit` and `oxlint` clean, `vite build` succeeds:

```
$ npx tsc -b --noEmit
(no output)

$ npx oxlint
(no output)

$ npm run build
✓ built in 956ms
```

Verified against a real docker-compose stack (order/inventory/payment/fulfillment/scenario-service
joined to the already-running `kafka-portfolio-project` compose project, since `kafka`/`postgres`
were already up from a prior session):

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order
{"id":"run-294","scenarioName":"standard-order","status":"RUNNING", ...}

$ curl -s http://localhost:8085/demo/scenario-runs/run-294   # after it completed
{"status":"COMPLETED", ... "timeline":[
  {"kind":"HTTP","label":"PUT /demo/payment-behavior", ...},
  {"kind":"HTTP","label":"POST /api/orders", ...},
  {"kind":"EVENT","label":"OrderCreated","detail":{"producer":"order-service","topic":"orders.events",...}},
  {"kind":"EVENT","label":"InventoryReserved","detail":{"producer":"inventory-service","topic":"inventory.events",...}},
  {"kind":"STATE_CHANGE","label":"Order INVENTORY_RESERVED", ...},
  {"kind":"STATE_CHANGE","label":"Order PAYMENT_PENDING", ...},
  {"kind":"EVENT","label":"PaymentRequested","detail":{"producer":"order-service","topic":"orders.events",...}},
  {"kind":"EVENT","label":"PaymentAuthorized","detail":{"producer":"payment-service","topic":"payments.events",...}},
  {"kind":"STATE_CHANGE","label":"Order PAID", ...},
  {"kind":"STATE_CHANGE","label":"Order FULFILLMENT_PENDING", ...},
  {"kind":"EVENT","label":"ShipmentCreated","detail":{"producer":"fulfillment-service","topic":"fulfillment.events",...}},
  {"kind":"STATE_CHANGE","label":"Order FULFILLED", ...}
]}

$ curl -s -X POST http://localhost:8085/demo/scenarios/payment-failure
$ curl -s http://localhost:8085/demo/scenario-runs/run-295   # after it completed, status COMPLETED
# timeline includes PaymentRejected (producer=payment-service) then InventoryReleased
# (producer=inventory-service) — the compensation path
```

Loaded both runs (`/scenario-runs/run-294`, `/scenario-runs/run-295`) in a headless Chromium via a
throwaway Playwright script (`npx playwright install chromium`, deleted after use), in both
`colorScheme: 'light'` and `colorScheme: 'dark'` contexts, and inspected full-page screenshots:

- `run-294` (`standard-order`, linear success path): badges render in order-blue → inventory-taupe →
  payment-violet → fulfillment-teal sequence, matching the actual producer sequence in the JSON
  above; connectors between them are labeled `inventory.events`, `orders.events`, `payments.events`,
  `fulfillment.events` respectively (the real `detail.topic` values); unattributed `STATE_CHANGE` rows
  render without a badge and without breaking the connector chain.
- `run-295` (`payment-failure`, rejection + compensation path): shows the same pattern through
  `PaymentRejected`, then a payment→inventory connector labeled `inventory.events` for the
  `InventoryReleased` compensating event. The `DELETE /demo/payment-behavior` HTTP row in between
  correctly attributes to payment (matches the `/demo/payment-behavior` path regex for any method).
- Dark mode: badge borders/backgrounds and connector dashed-pill labels stay legible against the
  dark background; no contrast or clipping issues observed.

Confirmed the `EventProjectionConsumer.java` claims from the brief directly by reading the file: the
`PRODUCER_BY_TOPIC` map is exactly the four `<service>-service` strings this code relies on, and its
javadoc states a `'consumed'` phase is deliberately never recorded (would require cross-schema reads
db-ownership.md forbids) — confirmed by grep that `"consumed"` appears nowhere else in any service's
Java source. `detail.consumer` is therefore never populated by anything in this codebase today, so
`attributeService` correctly never reads it.

Also grepped every `recordHttp(...)` call site under
`services/scenario-service/src/main/java/com/orderfulfillment/scenario/scenarios/*.java` to confirm
the closed set of HTTP labels (`POST /api/orders`, `PUT`/`DELETE /demo/payment-behavior`,
`POST /demo/inventory/{sku}/restore`, `POST`/`GET /demo/consumers/{id}/pause|resume`) and read
`ConsumerOutageScenario.java`'s javadoc to confirm `/demo/consumers/...` always targets Inventory
Service specifically in every scenario that calls it (not a generic "some service's consumer").

An incidental infrastructure note from this verification pass: bringing up all five backend app
services plus frontend with `--build` against an already-running `kafka`/`postgres`/`grafana`/
`prometheus` OOM-killed the `kafka` container (exit 137) — this Docker Desktop VM is capped at
3.825 GiB and two other unrelated containers (`zen_pike`, `gallant_wilbur`, apparently from other
concurrent work in this repo) were also using memory at the time. Restarted `kafka` with
`docker compose start kafka` once the build phase settled; it came back healthy and stayed healthy
for the rest of verification. Did not touch `zen_pike`/`gallant_wilbur` since they predate this
session.

## Judgment calls

- **Per-row badges + connector arrows, not a separate summary strip.** The issue's wording ("per
  step which service... and which topic/endpoint") plus the brief's explicit instruction that the
  new layer sits "above/alongside" the existing detail rows "not a replacement" pointed at augmenting
  the real per-entry list in place, rather than building a second, collapsed diagram that could drift
  from what the raw entries actually show. Rejected building an additional aggregate flow-chain
  component above the list — it would either duplicate the per-row information or require choosing
  which entries to collapse, which risks misrepresenting entries that got hidden in the collapse.
- **Connector arrow label uses the arriving entry's own topic/endpoint, not the departing one.** When
  the service changes between entry N and entry N+1, the connector renders immediately before entry
  N+1 and is labeled with entry N+1's own `flowRoutingLabel`. This is the entry that literally
  represents arrival at the new service, so its topic/endpoint is genuinely "what this hop routed
  through" rather than an inferred value.
- **STATE_CHANGE entries and non-attributable HTTP entries (e.g. `"Burst order submission
  complete"`) render with no badge (`visibility: hidden`, preserving layout) and don't break the
  connector chain** — the algorithm compares each attributed entry's service only against the
  previous *attributed* entry's service, skipping over unattributed ones in between. This matches the
  brief's instruction not to force an attribution that isn't real.
- **Split `scenarioFlow.tsx` into `scenarioFlow.ts` (logic) + `ServiceIcon.tsx` (JSX)** after oxlint's
  `react(only-export-components)` warning on the combined file. Kept it as a warning-free split
  rather than accepting the warning, since "oxlint clean" was an explicit verification bar in the
  brief.
- **No `--space-*` tokens used in the new CSS**, despite `STYLE_GUIDE.md` describing that scale.
  Grepped `index.css` and confirmed the scale is still an unassigned placeholder on this branch (only
  the four `--color-service-*` tokens have real values so far) — inventing my own values for tokens
  someone else's work is meant to assign would have created exactly the collision the styling
  contract exists to prevent. Used the same bare-pixel convention the rest of `index.css` already
  uses instead.
- **Joined the already-running `kafka`/`postgres`/`grafana`/`prometheus` containers via
  `docker compose -p kafka-portfolio-project` from the worktree** rather than starting a second,
  differently-named compose project (which would have doubled every container and likely exhausted
  the 3.825 GiB Docker VM outright). Confirmed the worktree's `docker-compose.yml` was byte-identical
  to the main checkout's before doing this.

## Deliberately not covered

- **`duplicate-event`, `consumer-outage`, `out-of-stock`, `inventory-contention`,
  `poison-message`, `high-volume` scenarios were not individually run and screenshotted** — only
  `standard-order` (linear success) and `payment-failure` (rejection/compensation) were exercised
  live, per the brief's "at least two different scenarios" instruction. The attribution logic was
  verified by reading every `recordHttp(...)` call site and the `PRODUCER_BY_TOPIC` map directly
  (see above), so the other scenarios' HTTP/EVENT entries should attribute correctly by the same
  rules, but that's read-verification, not a live run of each one.
- **`duplicate-event`'s non-linear replay is not specially handled**, per the brief's explicit
  out-of-scope note. A duplicate publish of the same event just re-attributes to the same service a
  second time under the current linear-chain algorithm; it does not render as a loop-back or get
  flagged as a replay. Generalizing the connector algorithm to a graph (to handle that and other
  non-linear flows) would need a different data structure than "walk the array, compare to the
  previous attributed service" — out of scope here per the brief.
- **No automated/unit test was added** for `attributeService`/`flowRoutingLabel` — this frontend
  package has no test framework wired up yet (`docs/planning` and the CI workflow both note
  lint+build is what currently exists; adding a test framework is out of scope for this ticket).
  Verification is the live-stack run above plus the direct source-reading described in "How this was
  verified."
- **Mobile/narrow-viewport rendering of the connector row was not checked** — screenshots were taken
  at 1100px width only, matching the existing page's implicit desktop-first layout (no responsive
  breakpoints exist elsewhere on this page today).
