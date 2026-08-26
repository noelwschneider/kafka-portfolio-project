# Issue #32 — price column on the New Order form's inventory table

## What changed

- `docs/openapi/order-service.yaml` — added `GET /api/prices` (new `prices` tag, new `SkuPrice`
  schema: `{sku, unitPrice}`). Documents that the endpoint is read-only, backed by the same seeded
  catalog `POST /api/orders` already prices from, and is never consulted at checkout time.
- `docs/db-ownership.md` — "Where prices come from" section gains one sentence noting the read-only
  endpoint now exists; ownership of the price map is unchanged (still Order Service, still no price
  column on `inventory_items`).
- `docs/CHANGELOG-contracts.md` — new top entry dated 2026-08-26 broadcasting the contract change per
  the coordination protocol (what changed, why, who's affected, what's not changed).
- `services/order-service/src/main/java/com/orderfulfillment/order/SkuPriceCatalog.java` — added
  `allPrices()`, returning the four seeded `(sku, price)` pairs sorted by SKU via a `TreeMap`. The
  existing `priceFor(sku)` used by `OrderService.createOrder` is untouched.
- `services/order-service/src/main/java/com/orderfulfillment/order/PriceController.java` (new) —
  `@RestController` at `/api/prices`, one `GET` mapping, maps `SkuPriceCatalog.allPrices()` entries to
  `SkuPrice` DTOs. Deliberately a separate controller from `OrderController` rather than a method
  bolted onto it, since `/api/prices` is a sibling resource under `/api`, not a sub-resource of
  `/api/orders`.
- `services/order-service/src/main/java/com/orderfulfillment/order/dto/SkuPrice.java` (new) — record
  `(String sku, BigDecimal unitPrice)`.
- `services/order-service/src/test/java/com/orderfulfillment/order/PriceControllerIntegrationTest.java`
  (new) — Testcontainers-backed integration test asserting the endpoint returns exactly the four
  seeded SKUs, sorted, with prices matching `docs/db-ownership.md`'s table.
- `frontend/src/api/orders.ts` — added `SkuPrice` interface and `listPrices()` calling
  `GET /api/prices` on `ORDER_SERVICE_BASE_URL`.
- `frontend/src/pages/CreateOrderPage.tsx` — added a `useQuery(['prices'], listPrices)` call and a
  `priceBySku` lookup map; the inventory table gains a `Price` column (`$XX.XX`, or `—` if a SKU has
  no seeded price) between SKU and Available. Removed the stale comment explaining why there was no
  price column.

## How this was verified

TypeScript and frontend production build:

```
$ npx tsc --noEmit -p .
(no output — compiles clean)

$ npx vite build
✓ built in 2.41s
```

Backend compiles clean:

```
$ cd services/order-service && mvn -q -DskipTests compile
(no output — success)
```

Backend integration tests (Testcontainers: real Postgres + real Kafka), run twice — the first run
lost its Kafka Testcontainers container to resource contention from a concurrent `docker compose
--build` I had running at the same time (`Timed out waiting for log output matching '.*Transitioning
from RECOVERY to RUNNING.*'`, and the compose stack's own `kafka` container was OOM-killed, exit 137,
during the same window). Re-ran serially after finishing the docker builds:

```
$ mvn -q test -Dtest=PriceControllerIntegrationTest,UnmappedRouteIntegrationTest
(exit 0, no [ERROR] lines — Testcontainers Postgres+Kafka started, Flyway migrated, Spring context
came up, all 3 tests — listsAllSeededPricesSortedBySku, unmappedPathReturns404NotInternalServerError,
wrongHttpMethodOnRealRouteReturns405NotInternalServerError — passed)
```

Verified against the real running docker-compose stack (already up before this session; rebuilt only
`order-service` and `frontend`, per the "rebuild only what your change touches" rule):

```
$ docker compose build order-service   # succeeded
$ docker compose up -d --no-deps order-service
$ curl -s -w '\nHTTP %{http_code}\n' http://localhost:8081/api/prices
[{"sku":"SKU-001","unitPrice":129.00},{"sku":"SKU-002","unitPrice":189.00},{"sku":"SKU-003","unitPrice":14.50},{"sku":"SKU-004","unitPrice":249.00}]
HTTP 200

$ curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8081/api/orders -H 'Content-Type: application/json' \
    -d '{"customerId":"demo-customer","items":[{"sku":"SKU-001","quantity":1}]}'
{"id":"order-20180","status":"PENDING","createdAt":"2026-08-26T16:05:29.624645003Z"}
HTTP 201   # order creation, the existing pricing path, still works unchanged
```

Visual verification with a throwaway Playwright script (installed already; deleted after use) against
the rebuilt `frontend` container at `http://localhost:5173`:

```
HEADERS: Product	SKU	Price	Available	
ROWS:
 - Developer Mug	SKU-003	$14.50	40	Add
 - USB-C Dock	SKU-002	$189.00	4	Add
 - Mechanical Keyboard	SKU-001	$129.00	0	Add
 - External SSD	SKU-004	$249.00	0	Add
```

Screenshot taken during the same run confirms the Price column renders inline in the New Order modal
next to SKU and Available, with real dollar values matching the seeded table.

## Judgment calls

- **New `PriceController` instead of a method on `OrderController`.** `/api/prices` is a sibling
  top-level resource under `/api`, not nested under `/api/orders` — putting it on `OrderController`
  (whose `@RequestMapping` is `/api/orders`) would have required an awkward relative path. A small
  dedicated controller matches how the OpenAPI doc models it as its own path.
- **`allPrices()` returns `List<Map.Entry<String, BigDecimal>>` rather than a `List<SkuPrice>`
  directly.** Kept the DTO mapping in the controller so `SkuPriceCatalog` (in the service's core
  package) doesn't depend on the `dto` package — matches the existing separation where DTOs are
  controller-facing shapes, not returned by domain/catalog classes.
- **`GET /api/prices` was added under the `orders` tag's sibling `prices` tag, not folded into
  `orders`.** It's conceptually a different resource (a catalog lookup, not an order operation), and
  a reader scanning tags in the OpenAPI doc should be able to tell "orders" from "catalog" endpoints
  at a glance.
- **Price formatting in the frontend (`$XX.XX` via `toFixed(2)`) rather than reusing a shared
  currency formatter.** No shared currency-formatting utility exists elsewhere in the frontend
  (checked `frontend/src/pages/OrdersListPage.tsx`'s own amount rendering, which does the same
  `$` + raw number pattern) — introducing one would be scope creep for a single new column.
- **Restarted `kafka` after it was OOM-killed by concurrent build load, and let compose recreate the
  four dependent services (`inventory-service`, `payment-service`, `fulfillment-service`,
  `scenario-service`) that `depends_on: kafka` with `condition: service_healthy`.** This was a
  restart, not a rebuild — no image changed for those four, they just reconnected their Kafka
  consumers, and `docker-compose.yml`'s named volume for `kafka` meant no topic/offset data was lost.
  Left everything in the same "up and healthy" state I found it in.

## Deliberately not covered

- No price editing, checkout-time price validation, or write path — matches the explicit scope
  boundary in the task. `POST /api/orders` still prices independently server-side from the same
  in-process `SkuPriceCatalog`; `GET /api/prices` is never consulted by it.
- No change to Inventory Service's schema, API, or ownership — `InventoryItem` still carries no price
  field, as instructed.
- Did not add a currency column or multi-currency support — the project has a single implicit
  currency throughout (`docs/db-ownership.md`), unchanged here.
- Did not investigate the unrelated modified files present in `git status` at the time of this work
  (`frontend/src/index.css`, `frontend/src/pages/OrdersListPage.tsx`,
  `infrastructure/kubernetes/production/*`, `services/common/pom.xml`,
  `services/scenario-service/**`, `docs/planning/README.md`) — these appear to be other in-flight
  Sprint 6 work already present in the working tree before this session started, not something this
  task touched or should touch.
- Did not add a dedicated unit test for `SkuPriceCatalog.allPrices()` in isolation — it's exercised
  end-to-end by `PriceControllerIntegrationTest`, which is the same level the existing
  `priceFor(sku)` method is tested at (only indirectly, via `OrderServiceIntegrationTest`'s order
  creation assertions).
