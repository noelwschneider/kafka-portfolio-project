# Phase 1 Report — Modular Monolith / Core Domain

**Date:** 2026-08-17
**Scope:** `docs/planning/implementation-phases.md`'s Phase 1 (Modular Monolith / Core Domain) and
`docs/planning/execution-plan.md` §4's Phase 1 row.
**Output:** one Spring Boot application (`services/monolith/`) proving the order/inventory/payment/
fulfillment workflow synchronously, plus a minimal React/TypeScript UI (`frontend/`). No Kafka, no
Kubernetes, no Scenario Service, no idempotency/outbox/DLQ machinery — those are Phases 2, 3, 4, 6,
8.

No file under `docs/planning/` or the frozen contracts (`docs/openapi/`, `docs/events/`,
`docs/order-state-machine.md`, `docs/db-ownership.md`, `docs/scenarios.md`, `docs/adr/`,
`docs/architecture-diagram.md`) was modified.

---

## 1a. Addendum — upgraded to Spring Boot 4.1.0 (post-review)

Built originally against Spring Boot 3.3.4, per `docs/planning/execution-plan.md` §7's "Spring Boot
3.x". At the user's request, upgraded to **4.1.0** (current GA, not a milestone/RC) after
confirming the whole app and test suite still work. This is a contract-relevant deviation from
§7's pin, flagged here per the coordination protocol rather than edited into `execution-plan.md`
directly (it's a frozen `docs/planning/` file — flag, don't edit, per `.claude/CLAUDE.md`).
**Recommended fix for whoever next touches that file:** change "Spring Boot 3.x" to "Spring Boot
4.x" in the Backend list.

What the upgrade actually changed, for Phase 3+ agents building on this code:

- **`pom.xml`**: parent version `3.3.4` → `4.1.0`. No other dependency versions needed pinning —
  the parent BOM handles them.
- **`org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor`**
  moved to **`org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor`**
  (Boot 4 split `spring-boot-autoconfigure` into many small per-concern modules, e.g.
  `spring-boot-jpa`, `spring-boot-data-jpa`, `spring-boot-hibernate`, `spring-boot-jdbc`, pulled in
  transitively by the starters as before — no new explicit dependency was needed, only the import
  changed). Affects `common/SchemaMigrationJpaDependencyConfig.java`.
- **`TestRestTemplate` was removed** (Spring Framework 7's replacement for the MockMvc/real-server
  test-client family is `RestTestClient`, in `org.springframework.test.web.servlet.client`, spec'd
  in Spring's own docs as the fluent WebTestClient-style successor). Unlike `TestRestTemplate`,
  Boot's test starter does not auto-inject a `RestTestClient` bean bound to the random port — each
  test builds its own via `RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()`
  (done once in `AbstractIntegrationTest`'s `@BeforeEach`). The fluent
  `.get().uri(...).exchange().expectStatus()...expectBody(Type.class).returnResult().getResponseBody()`
  chain replaces `getForObject`/`postForEntity`/`exchange` throughout the three integration tests.
  One incidental improvement: asserting a `404` no longer needs the `String.class`-then-status-check
  workaround `TestRestTemplate` required for non-2xx bodies — `expectStatus().isNotFound()` alone
  suffices.
- No entity, DTO, controller, or service code needed any change — the domain code was not written
  against anything Boot-4-incompatible to begin with (no `javax.*` remnants, no deprecated
  MVC/JPA APIs in scope for this phase).
- Re-verified all 22 tests pass (`mvn test`, JDK 21) and re-ran the same manual/`curl` walkthrough
  from §4/§5 below against the running 4.1.0 app with identical results.

---

## 1. Repository layout (recorded per the prompt, not written down elsewhere)

```
services/monolith/          one Spring Boot app (Maven, single module — see §3.1 judgment call)
  src/main/java/com/orderfulfillment/monolith/
    order/                  controller, service, repository, entity, dto — mirrors order_service schema
    inventory/               "        "        "         "      "     — mirrors inventory_service schema
    payment/                 "        "        "         "      "     — mirrors payment_service schema
    fulfillment/              "        "        "         "      "     — mirrors fulfillment_service schema
    common/                 ApiError/GlobalExceptionHandler, correlation-id filter, id generator,
                            multi-schema Flyway migration runner
  src/main/resources/db/migration/{order,inventory,payment,fulfillment}/
                            one Flyway history per schema, per docs/db-ownership.md
  src/test/...              unit tests (no Spring context) + Testcontainers integration tests
frontend/                   Vite + React + TypeScript + TanStack Query (path unchanged across phases)
```

`services/monolith/` is temporary: Phase 3 dissolves it into `services/order-service/`,
`services/inventory-service/`, etc. Each domain package is already structured
controller/service/repository/entity/dto internally, and every cross-domain call goes through a
narrow service-bean method (`InventoryService.reserve/release`, `PaymentService.authorize`,
`FulfillmentService.createShipment`) rather than reaching into another package's repositories or
entities — so Phase 3's extraction should be closer to "move a package to a new top-level module"
than a redesign, per the prompt's stated goal.

**Judgment call:** the pinned-tech table says "Maven (multi-module)", which describes the
*repository's* eventual build (Phase 3's four independent service modules). Phase 1 is explicitly
"one Spring Boot application" (implementation-phases.md), so `services/monolith/` is a single
non-multi-module Maven project. Not a deviation from the pin — the pin describes the end state this
project is building toward, and multi-module Maven has nothing to attach to yet with one deployable
app.

---

## 2. Files created

### Backend (`services/monolith/`)

- `pom.xml` — Spring Boot 3.3.4 parent, Java 21, spring-boot-starter-web/data-jpa/validation,
  postgresql driver, flyway-core + flyway-database-postgresql, JUnit 5, Testcontainers
  (postgresql + junit-jupiter modules, BOM 1.21.4)
- `src/main/resources/application.yml` — datasource, `ddl-auto: validate`, `spring.flyway.enabled:
  false` (see §3.2)
- `common/` — `ApiError`, `ApiException` + 3 subtypes, `GlobalExceptionHandler`
  (`@RestControllerAdvice`), `CorrelationIdFilter` + `CorrelationIdHolder` (rule 17), `IdGenerator`,
  `SchemaMigrationRunner` + `SchemaMigrationJpaDependencyConfig` (multi-schema Flyway, see §3.2),
  `WebConfig` (local-dev CORS for the Vite dev server)
- `order/` — `OrderStatus` enum, `OrderEntity`/`OrderItemEntity`/`OrderStatusHistoryEntity`, 3
  repositories, `SkuPriceCatalog`, `OrderPersistence` (per-step transactions), `OrderService`
  (orchestration), `OrderController`, 8 DTOs
- `inventory/` — `InventoryItemEntity` (with `@Version`), `InventoryReservationEntity`,
  `ReservationStatus`, 2 repositories, `InventoryReservationExecutor` (transactional reserve/release),
  `InventoryService` (optimistic-lock retry wrapper), `InventoryController`, `OrderLine`, `Shortage`,
  `ReservationResult`, 2 DTOs
- `payment/` — `PaymentAttemptEntity`, `PaymentAttemptStatus`, `PaymentFailureReason`, repository,
  `PaymentBehaviorStore` (in-memory demo override), `PaymentOutcome`, `PaymentService`,
  `PaymentController` (`/api`), `PaymentDemoController` (`/demo`), 3 DTOs
- `fulfillment/` — `ShipmentEntity`, repository, `FulfillmentService`, `FulfillmentController`, 1 DTO
- `db/migration/{order,inventory,payment,fulfillment}/*.sql` — 5 migration files (4 schemas + 1 seed
  data), matching `docs/db-ownership.md` §3 column-for-column

### Tests (`services/monolith/src/test/`)

- `order/OrderStatusTest`, `order/dto/CreateOrderRequestValidationTest` — domain rules, no Spring
  context
- `inventory/InventoryReservationExecutorTest` — all-or-nothing reservation, unknown-SKU shortage,
  release; mocked repositories
- `inventory/InventoryServiceOptimisticLockTest` — retry-then-succeed and retry-exhausted-then-throw
  on `ObjectOptimisticLockingFailureException`
- `payment/PaymentServiceTest` — default success, global/order-scoped `REJECT`, `RETRYABLE_ERROR`,
  clearing the override
- `AbstractIntegrationTest`, `HappyPathIntegrationTest`, `OutOfStockIntegrationTest`,
  `PaymentRejectionIntegrationTest` — Testcontainers PostgreSQL, full HTTP round trip via
  `TestRestTemplate`

### Frontend (`frontend/`)

- Vite + React 19 + TypeScript scaffold (`npm create vite@latest -- --template react-ts`) +
  `@tanstack/react-query`
- `src/api/client.ts` — fetch wrapper, `ApiError`/`ApiRequestError`
- `src/api/orders.ts`, `src/api/inventory.ts` — typed clients mirroring the frozen OpenAPI schemas
- `src/pages/OrdersListPage.tsx`, `CreateOrderPage.tsx`, `OrderDetailPage.tsx` — the three required
  pages; nothing else (no scenario UI, no event explorer — those are Phase 5)
- `src/components/StatusBadge.tsx`, `src/App.tsx` (state-based view switch, no router dependency),
  `src/index.css` (replaced the Vite marketing template's styling with a plain data-table/form look)
- `.env.example` (`VITE_API_BASE_URL`)

### Project config

- `.claude/launch.json` — dev-server launch config for the frontend, added so the app can be
  previewed; not part of Phase 1's deliverables but needed to verify this phase

### This report

- `docs/agent-reports/phase-1.md`

---

## 3. Judgment calls

### 3.1 Single-module Maven, not multi-module — see §1.

### 3.2 Multi-schema Flyway via a custom runner, not Spring Boot's Flyway auto-configuration

Spring Boot's built-in Flyway support drives one schema/history. `docs/db-ownership.md` requires one
schema and one migration history **per owning service**, even inside one app this phase. Built
`SchemaMigrationRunner` (`@Component("schemaMigrator")`, `@PostConstruct`) which runs four
independent `Flyway.configure()...load().migrate()` calls, one per schema, each producing its own
`flyway_schema_history` table inside that schema.

This surfaced a real ordering bug during verification: a `CommandLineRunner` runs *after* Spring's
context refresh, but Hibernate's `ddl-auto: validate` check runs *during* refresh (when the
`entityManagerFactory` bean is created) — so migrations hadn't happened yet and every integration
test failed with `SchemaManagementException: missing table`. Fixed by moving the migration into
`@PostConstruct` and adding `SchemaMigrationJpaDependencyConfig`, which registers Spring Boot's own
`EntityManagerFactoryDependsOnPostProcessor("schemaMigrator")` — the same mechanism Spring Boot's own
Flyway auto-configuration uses to make `entityManagerFactory` wait for `flyway` — so this generalizes
a pattern already implicit in the framework rather than inventing a new one.

### 3.3 No default JPA schema; every entity's `@Table(schema = ...)` is explicit

Kept the four domains genuinely separated at the JPA level (matching `docs/db-ownership.md`'s "no
cross-schema queries" rule) rather than picking one `default_schema` and leaving the other three
implicit.

### 3.4 Order Service validates SKU/price at request time; Inventory Service defends independently

`docs/openapi/order-service.yaml`'s `ApiError.code` enum for Order Service already lists
`UNKNOWN_SKU`, and `docs/db-ownership.md`'s "Where prices come from" section makes Order Service the
sole owner of the SKU→price map — so an order line naming a SKU outside that map is rejected as a
`400 UNKNOWN_SKU` at creation, before inventory is ever consulted. Inventory Service *also* reports
`UNKNOWN_SKU` as a reservation-failure reason (`InventoryReservationFailed.reason`,
`docs/events/event-catalog.md`) for a SKU it doesn't recognize in its own `inventory_items` table —
kept as defense-in-depth for the case where the two catalogs diverge, even though in Phase 1's seed
data they never do.

### 3.5 Per-step transactions in the synchronous orchestrator, not one big transaction

`OrderService.createOrder()` is not itself `@Transactional`. Each step
(`OrderPersistence.createPendingOrder`/`appendStatus`, `InventoryReservationExecutor.attemptReserve`/
`release`, `PaymentService.authorize`, `FulfillmentService.createShipment`) commits in its own
`REQUIRES_NEW` transaction. This mirrors what separate REST/event calls between real services will
look like once Phase 3 extracts them — each "service" commits its own local state independently,
rather than one cross-domain database transaction that Phase 3 could never reproduce once the
domains are different databases. It also means a self-invocation trap had to be avoided twice: a
`@Transactional` method called via `this.` from within the same class bypasses Spring's proxy and
silently runs with no transaction at all. `InventoryReservationExecutor` and `OrderPersistence` are
therefore separate `@Component` beans injected into `InventoryService`/`OrderService`, not private
methods on those classes.

### 3.6 `PaymentBehavior`'s `RETRYABLE_ERROR` mode maps to the `FAILED` terminal state this phase

The frozen spec (`docs/openapi/payment-service.yaml`) describes `RETRYABLE_ERROR` as triggering
Phase 4's bounded retries + `payments.dlq`, leaving the order in `PAYMENT_PENDING` until retries are
exhausted. Phase 1 has no retry/DLQ machinery. Rather than silently drop the mode or fake a retry
loop, `PaymentService.authorize` returns a `PROVIDER_ERROR` outcome and `OrderService` drives the
order straight to `FAILED` (`docs/order-state-machine.md` transition 9 — "non-retryable processing
failure"). Documented inline in `PaymentOutcome.Kind.PROVIDER_ERROR`'s Javadoc and here, not left as
an unexplained shortcut; the reservation is **not** compensated in this path (no event fires today
that would trigger it), which is worth revisiting when Phase 4 gives this mode its real behavior.

### 3.7 `/api/orders/stream` (SSE) not implemented this phase

`docs/openapi/order-service.yaml` freezes the endpoint, but the prompt scopes the frontend to
"create order, list orders, order detail" only, and the whole workflow completes synchronously
within one request in Phase 1 — there is no asynchronous transition for a stream to usefully report
yet. Left for Phase 5 ("Add SSE/live updates", `implementation-phases.md`), once Phase 2 makes state
changes actually arrive out of band. Not a contract violation: the endpoint's shape isn't touched,
it's just unimplemented.

### 3.8 `/demo/consumers` (pause/resume) not implemented; `/demo/payment-behavior` is

Inventory Service's and Fulfillment Service's `/demo/consumers/*` endpoints control Kafka listener
containers that don't exist until Phase 2 — implementing them now would mean fabricating a "paused"
flag with no consumer behind it. Left unimplemented. `/demo/payment-behavior`, by contrast, does not
depend on Kafka at all — it is an in-memory override read directly by the synchronous
`PaymentService.authorize` call — and Phase 1's own exit criteria require a **real, reproducible**
payment-rejection path (not a hidden test-only hook), so it was built now, isolated under `/demo`
per Agent Rule 9.

### 3.9 `POST /api/orders` returns the actual resulting status, not the spec's `PENDING` example

This is the deliberate, prompt-specified deviation: in Phase 1 the whole workflow runs synchronously
inside the request, so returning a hardcoded `PENDING` would be stale the instant the response left
the server. `OrderController`/`OrderService` return the real terminal (or `FAILED`) status. Phase 2
replacing these direct calls with real Kafka-driven asynchrony is what makes the response go back to
matching the spec's `PENDING` example — see `OrderService`'s class Javadoc.

---

## 4. Exit criteria — verified, with reproduction steps

All four criteria from `implementation-phases.md`'s Phase 1 section were checked by running the
actual test suite and by exercising the real running app (backend + browser UI + `curl`), not by
inspection alone.

### "Happy-path order (adequate stock, payment succeeds) reaches FULFILLED"

- **Automated:** `HappyPathIntegrationTest` — `POST /api/orders` for 1× SKU-001, asserts the response
  status is `FULFILLED`, `GET /api/orders/{id}` shows the full transition sequence `PENDING →
  INVENTORY_RESERVED → PAYMENT_PENDING → PAID → FULFILLMENT_PENDING → FULFILLED`, the payment attempt
  is `AUTHORIZED`, a shipment exists, and `inventory_items.reserved_quantity` increased by exactly 1.
- **Manual, against the running app:** placed an order for SKU-001 through the actual frontend UI
  (Vite dev server + `mvn spring-boot:run` + a real `postgres:16-alpine` container) and watched it
  render `FULFILLED` with the same 6-entry status history in the browser.
- **Reproduce:**
  ```bash
  cd services/monolith && JAVA_HOME=<a JDK 21 install> mvn test -Dtest=HappyPathIntegrationTest
  ```

### "An order requesting more than available stock reaches REJECTED_OUT_OF_STOCK and no payment/shipment side effects occur"

- **Automated:** `OutOfStockIntegrationTest` — requests one more unit of SKU-004 than is currently
  free, asserts `REJECTED_OUT_OF_STOCK`, a 2-entry history (`PENDING → REJECTED_OUT_OF_STOCK`),
  `GET /api/payments/{id}` and `GET /api/shipments/{id}` both `404`, and
  `inventory_items.reserved_quantity`/`available_quantity` for SKU-004 unchanged from their
  pre-request values.
- **Manual:** `curl -X POST /api/orders` for 5× SKU-004 (2 in stock) against the running app returned
  `{"status":"REJECTED_OUT_OF_STOCK", ...}` and `GET /api/inventory` afterward showed SKU-004 still
  at `reservedQuantity: 0`.
- **Reproduce:** `mvn test -Dtest=OutOfStockIntegrationTest`

### "An order that clears inventory but fails payment reaches PAYMENT_FAILED, and its inventory reservation is released (stock count actually returns to its pre-reservation value)"

- **Automated:** `PaymentRejectionIntegrationTest` — arms `PUT /demo/payment-behavior` with
  `{"mode":"REJECT"}`, places an order for SKU-002, asserts `PAYMENT_FAILED`, a 4-entry history
  (`PENDING → INVENTORY_RESERVED → PAYMENT_PENDING → PAYMENT_FAILED`), the payment attempt is
  `REJECTED` with the given `failureReason`, no shipment (`404`), and — the part that actually proves
  compensation, not just a status label — `inventory_items.reserved_quantity` for SKU-002 is back to
  its **pre-order** value, read from the API both before and after the order rather than assumed.
- **Manual:** same sequence via `curl` against the running app: `PUT /demo/payment-behavior`
  `{"mode":"REJECT","failureReason":"CARD_DECLINED"}` → `POST /api/orders` for 1× SKU-002 → response
  `PAYMENT_FAILED` → `GET /api/inventory` showed SKU-002 back at `reservedQuantity: 0` (from 1
  immediately after reservation) → `DELETE /demo/payment-behavior` reset the override.
- **Reproduce:** `mvn test -Dtest=PaymentRejectionIntegrationTest`

### "The test suite passes and actually exercises all three paths above, not just the happy path"

```
$ mvn test
...
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
22 tests: 6 validation, 2 state-machine, 4 reservation-executor, 2 optimistic-lock-retry, 5
payment-simulator, 3 full-stack integration tests (one per required path). Full command:
```bash
cd services/monolith
JAVA_HOME=<a JDK 21 install> mvn test
```

**Environment note, worth recording for whoever runs this next:** this machine's default `JAVA_HOME`
is JDK 26. Mockito's bytecode instrumentation (`byte-buddy`) does not yet support JDK 26, so plain
`mvn test` under the default JDK fails every test that mocks a concrete class, with a `Byte Buddy
could not instrument` error — unrelated to this project's code. Point `JAVA_HOME` at a JDK 21 install
(the pinned LTS version) and it passes cleanly; this machine has one at
`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`. Also bumped the Testcontainers BOM
from 1.20.1 to 1.21.4 in `pom.xml`: 1.20.1's bundled `docker-java` client could not parse this
machine's Docker Desktop's `/info` response (API version 1.55) and failed every integration test with
"Could not find a valid Docker environment"; 1.21.4 handles it. Neither change affects the pinned
Java 21 / Maven / Testcontainers stack itself.

---

## 5. Frontend verification

Ran the actual app (`mvn spring-boot:run` against a real `postgres:16-alpine` container, `npm run
dev` for the Vite dev server) and drove it through the Browser tool rather than only building it:

- **Create order:** SKU dropdown populated live from `GET /api/inventory` (not a fabricated list);
  placing an order for SKU-001 navigated straight to the order-detail view showing `FULFILLED` and
  the full 6-entry status history.
- **List orders:** the orders table showed the created order with correct status/total/timestamps,
  polling every 4s.
- **Order detail:** items table and status history render correctly with human-readable status
  badges (color-coded pending/success/failure).
- Found and fixed a real bug this way: the backend had no CORS configuration, so every frontend
  request was blocked by the browser (`No 'Access-Control-Allow-Origin' header`) even though `curl`
  against the same endpoints worked fine. Added `WebConfig` (`common/WebConfig.java`) allowing
  `http://localhost:*` origins — a local-dev convenience, not a production security decision; Phase 5
  or later should revisit allowed origins once the real deployment topology exists.
- `npm run build` (`tsc -b && vite build`) succeeds with no type errors.

Out-of-stock and payment-rejection were verified via `curl` against the same running backend (§4)
rather than re-driven through the UI a second time, since the UI reads the same `GET
/api/orders/{id}` the integration tests already assert against.

---

## 6. Inconsistencies / gaps found in the frozen contracts or `docs/planning/`

None that required a contract change. Two things worth flagging for whoever picks up Phase 2/3,
though neither blocked this phase:

1. **`docs/openapi/order-service.yaml`'s `POST /api/orders` description says the endpoint "returns
   immediately... does not wait for inventory, payment, or fulfillment."** That's correct for the
   target (Phase 2+) design and was already flagged by this prompt as a known Phase 1 deviation
   (§3.9 above) — recording it here too since it's the kind of thing a future reader diffing the spec
   against Phase 1's actual responses would otherwise flag as a bug. Not a contract error; the spec
   describes the end state correctly.
2. **No `/demo` reset endpoint exists yet for payment behavior beyond `DELETE
   /demo/payment-behavior`.** Fine for Phase 1 (only one override exists to reset), but Scenario
   Service's future `POST /demo/reset` (per `docs/scenarios.md`) will need to call this same clear
   operation — worth Phase 3+ confirming `PaymentBehaviorStore.clear()` is what that endpoint should
   invoke rather than reinventing it.

---

## 7. Reproducing this phase from a clean clone

```bash
# Backend
docker run -d --name orderfulfillment-dev \
  -e POSTGRES_DB=orderfulfillment -e POSTGRES_USER=orderfulfillment -e POSTGRES_PASSWORD=orderfulfillment \
  -p 5432:5432 postgres:16-alpine
cd services/monolith
JAVA_HOME=<a JDK 21 install> mvn spring-boot:run
# -> http://localhost:8080, seeded inventory, Flyway-migrated 4-schema database

# Frontend (separate terminal)
cd frontend
npm install
npm run dev
# -> http://localhost:5173

# Tests
cd services/monolith
JAVA_HOME=<a JDK 21 install> mvn test
```
