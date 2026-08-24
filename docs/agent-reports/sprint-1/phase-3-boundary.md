# Phase 3 Report — Extract Services (Boundary Definition)

**Date:** 2026-08-18
**Scope:** `docs/planning/implementation-phases.md`'s Phase 3 (Extract Services) — the *sequential
boundary-definition* stage per `docs/planning/execution-plan.md` §1.2/§4, not the parallel fan-out
that follows it.
**Output:** the Phase 1/2 monolith (`services/monolith/`) dissolved into four independently
buildable/runnable/stoppable Spring Boot services (`services/order-service`,
`services/inventory-service`, `services/payment-service`, `services/fulfillment-service`) plus one
shared library module (`services/common`), under a new root Maven aggregator (`pom.xml`). Each
service communicates with the others only via Kafka; each owns its own Postgres schema via Spring
Boot's ordinary Flyway auto-configuration. `services/monolith/` is deleted.

No file under `docs/planning/`, `docs/openapi/`, `docs/order-state-machine.md`,
`docs/db-ownership.md`, `docs/scenarios.md`, `docs/adr/`, `docs/architecture-diagram.md`, or
`docs/events/*` (existing content) was modified.

---

## 1. Repository layout (as built)

```
pom.xml                          new root aggregator (packaging=pom, parent=spring-boot-starter-parent
                                  4.1.0, modules=the five below, dependencyManagement imports the
                                  Testcontainers BOM once for all children)
services/
  common/                        new shared library module (packaging=jar, not a deployable app)
    src/main/java/com/orderfulfillment/common/
      ApiError, ApiException (+NotFoundException/ConflictException/ValidationApiException),
      GlobalExceptionHandler, CorrelationIdFilter, CorrelationIdHolder, IdGenerator, WebConfig
      events/   EventEnvelope, EventItem, ShortageItem, and one record per catalogued payload
      kafka/    EventCodec, EventPublisher, EventTypes, KafkaTopics, KafkaTopicConfig,
                UnsupportedEventVersionException
  order-service/                 port 8081 (docs/openapi/order-service.yaml)
    src/main/java/com/orderfulfillment/order/          controller/service/repository/entity/dto/
    src/main/resources/application.yml, db/migration/V1__orders.sql
    src/test/java/com/orderfulfillment/order/           unit tests + OrderServiceIntegrationTest
  inventory-service/              port 8082 (docs/openapi/inventory-service.yaml)
    src/main/java/com/orderfulfillment/inventory/...
    src/main/resources/application.yml, db/migration/{V1__inventory.sql,V2__seed_data.sql}
    src/test/java/com/orderfulfillment/inventory/...
  payment-service/                port 8083 (docs/openapi/payment-service.yaml)
    src/main/java/com/orderfulfillment/payment/...
    src/main/resources/application.yml, db/migration/V1__payments.sql
    src/test/java/com/orderfulfillment/payment/...
  fulfillment-service/            port 8084 (docs/openapi/fulfillment-service.yaml)
    src/main/java/com/orderfulfillment/fulfillment/...
    src/main/resources/application.yml, db/migration/V1__shipments.sql
    src/test/java/com/orderfulfillment/fulfillment/...
  monolith/                       DELETED — see §7
docker-compose.yml                unchanged (infra only: Postgres + Kafka)
```

Each service's internal package structure (controller/service/repository/entity/dto, plus the
Kafka consumer classes added in Phase 2) is carried over unchanged from the monolith's per-domain
packages — this was "move a package to a new top-level module," not a redesign, exactly as
Phase 1's report predicted it would be (`docs/agent-reports/phase-1.md` §1).

### Package naming judgment call

Each service's base Java package is `com.orderfulfillment.<domain>` (`order`, `inventory`,
`payment`, `fulfillment` — the same names the monolith already used for its domain packages), not
`com.orderfulfillment.orderservice` etc. The prompt's own literal directory names
(`order-service`, `inventory-service`, ...) support either reading; I kept the domain-name form
because it required zero package-statement renaming inside each moved file (only the `.monolith.`
segment needed to be dropped) and keeps `common`'s sibling relationship visually obvious
(`com.orderfulfillment.common` next to `com.orderfulfillment.order`, not nested under it). Each
service's `@SpringBootApplication` class uses `@ComponentScan(basePackages = {"com.orderfulfillment.<domain>",
"com.orderfulfillment.common"})` since `common` is a sibling package, not a subpackage, of the
scanned root.

---

## 2. `services/common/` — what moved, and what didn't

Moved, verbatim (package statement changed from `com.orderfulfillment.monolith.common` to
`com.orderfulfillment.common`; no other code changes):
- `ApiError`, `ApiException` + its 3 subtypes, `GlobalExceptionHandler`, `CorrelationIdFilter`,
  `CorrelationIdHolder`, `IdGenerator`, `WebConfig`
- `events/*` (envelope + all 8 catalogued payload records + `EventItem`/`ShortageItem`)
- `kafka/*` (`EventCodec`, `EventPublisher`, `EventTypes`, `KafkaTopics`, `KafkaTopicConfig`,
  `UnsupportedEventVersionException`)

**Did not move — deleted instead:** `SchemaMigrationRunner` and
`SchemaMigrationJpaDependencyConfig`. These existed only because Phase 1/2's single JVM needed four
independent Flyway histories simultaneously against one shared `DataSource`. Now each service's JVM
only ever touches its own schema, so each service's `application.yml` uses Spring Boot's ordinary
built-in Flyway auto-configuration (`spring.flyway.schemas: <its-own-schema>`,
`ddl-auto: validate` unchanged) — no custom runner, no `@PostConstruct` migration bean, no
`EntityManagerFactoryDependsOnPostProcessor` workaround. **This is a genuine simplification Phase 3
buys, not a cut corner** — the machinery Phase 1 built specifically to work around one JVM owning
four schemas is unneeded machinery the moment each JVM owns exactly one.

**Nothing was found that actually differs in behavior per service and had to stay duplicated.**
Every class in `common/` was byte-identical in intent across all four domains before this phase
(generic REST error handling, the wire-format envelope, correlation-id propagation) — none of it is
the "hidden magic" Agent Rule 16 warns against; it is ordinary shared-library boilerplate.

---

## 3. Database — schema-per-service, Boot's own Flyway auto-config

Split `db/migration/{order,inventory,payment,fulfillment}/*.sql` into each service's own
`src/main/resources/db/migration/`, content and filenames unchanged (`V1__orders.sql`,
`V1__inventory.sql` + `V2__seed_data.sql`, `V1__payments.sql`, `V1__shipments.sql`). Each service's
`application.yml` sets `spring.flyway.schemas: <schema>` matching `docs/db-ownership.md`'s table
(`order_service`, `inventory_service`, `payment_service`, `fulfillment_service`) and
`spring.datasource.url: jdbc:postgresql://localhost:5432/orderfulfillment` — all four still point
at the one shared local Postgres server/database, isolated only by schema, per db-ownership.md's
"share one PostgreSQL server, one schema per service" rule. Each entity's `@Table(schema = "...")`
annotation is untouched (Phase 1's judgment call to keep no default JPA schema was preserved).

**Finding requiring a fix beyond what the prompt anticipated:** Spring Boot 4.1's Flyway
autoconfiguration class is *not* pulled in by `flyway-core` + `flyway-database-postgresql` alone —
Boot 4 split it into a separate `org.springframework.boot:spring-boot-flyway` module (parallel to
the JPA/Kafka module splits Phases 1 and 2 already documented). Without it, `FlywayAutoConfiguration`
never registers, Flyway never runs, and Hibernate's `ddl-auto: validate` fails at startup with
`SchemaManagementException: missing table [<schema>.<table>]` — the failure looks exactly like a
migration-ordering bug (the same symptom Phase 1's report §3.2 describes) but has a different root
cause here. Fixed by adding `org.springframework.boot:spring-boot-flyway` as an explicit dependency
in each service's `pom.xml`, alongside `flyway-core`/`flyway-database-postgresql`. **Recommended
fix for whoever next touches `execution-plan.md` §7's Flyway line:** add the same kind of
parenthetical the Kafka line already has, naming `spring-boot-flyway` as the Boot-4-appropriate
module.

---

## 4. No synchronous inter-service REST calls

Unchanged from Phase 2: all four services communicate only through the Kafka event flow
(`docs/events/event-catalog.md` §3), moved into each owning service's package unchanged. The only
REST traffic crossing a service boundary is client-facing (frontend → Order Service, frontend →
Inventory Service). Verified by inspection (no service's code imports another service's package —
see §8's directory-ownership check) and by the process-level recovery test in §6, which only works
at all if Order Service never blocks on a synchronous call to Inventory Service.

---

## 5. Frontend

`frontend/src/api/client.ts`: `API_BASE_URL` (single) replaced with `ORDER_SERVICE_BASE_URL`
(`VITE_ORDER_SERVICE_URL`, default `http://localhost:8081`) and `INVENTORY_SERVICE_BASE_URL`
(`VITE_INVENTORY_SERVICE_URL`, default `http://localhost:8082`); `apiFetch` now takes the base URL
as its first parameter rather than reading a module-level constant. `orders.ts`'s three call sites
now pass `ORDER_SERVICE_BASE_URL`; `inventory.ts`'s one call site passes
`INVENTORY_SERVICE_BASE_URL`. `.env.example` updated to the two new variables.
`App.tsx`'s header subtitle updated ("Phase 3 — four independent services, Kafka-driven workflow").
`npm run build` (`tsc -b && vite build`) succeeds with no type errors.

---

## 6. Exit criteria — verified, with reproduction steps

All verification below used the actual built services (`mvn spring-boot:run` per service, real
`docker compose up -d` Postgres + Kafka) — not inspection alone — with a fresh Postgres volume
(`docker compose down -v && docker compose up -d`) so results weren't contaminated by leftover rows
from earlier manual Phase 2 verification against the same shared local database.

Environment note (repeated from Phase 1/2, still true): `JAVA_HOME` must point at a JDK 21 install
(`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` on this machine) — the default
JDK 26 breaks Mockito's byte-buddy instrumentation.

### "Each service builds and runs independently"

```bash
cd /path/to/repo
mvn -DskipTests package     # root aggregator: all 5 modules build, in dependency order
```
`BUILD SUCCESS`, confirmed with `services/monolith/` already deleted (so nothing could be
accidentally leaning on monolith code still sitting in the tree).

Standalone-start-with-siblings-down, actually exercised, not assumed: with `docker compose up -d`
running and **all four services stopped**, started only Inventory Service
(`cd services/inventory-service && mvn spring-boot:run`) and confirmed `GET localhost:8082/api/inventory`
returned `200` with the seeded catalog while `curl` to ports 8081/8083/8084 all failed to connect
(`000`). This is the strongest form of the criterion: not just "the other three happen to be
running," but confirmed no service breaks at startup or at request time when its siblings are
completely absent — no hidden runtime dependency between them.

### "Order processing still works after recovery"

Started all four services (`mvn spring-boot:run` × 4, separate terminals/background processes),
confirmed each logged `Started <X>ServiceApplication` on its own pinned port, then:

**Happy path**, end-to-end across real separate JVMs (not Testcontainers, not one process):
```bash
curl -s -X POST localhost:8081/api/orders -d '{"customerId":"demo-customer","items":[{"sku":"SKU-001","quantity":1}]}'
# {"id":"order-20001","status":"PENDING",...}
curl -s localhost:8081/api/orders/order-20001   # ~1s later
# status: FULFILLED, full 6-entry history PENDING -> INVENTORY_RESERVED -> PAYMENT_PENDING ->
# PAID -> FULFILLMENT_PENDING -> FULFILLED, each event-caused entry carrying a real sourceEventId
```

**Out-of-stock**: `POST` 10× `SKU-004` (2 in stock) → `REJECTED_OUT_OF_STOCK`, 2-entry history.

**Payment rejection**: armed `PUT localhost:8083/demo/payment-behavior {"mode":"REJECT","failureReason":"CARD_DECLINED"}`,
ordered 1× `SKU-002` → `PAYMENT_FAILED`, 4-entry history, `GET localhost:8082/api/inventory/SKU-002`
confirmed `reservedQuantity` back to its pre-order value (read before and after, not assumed).
Cleared the override afterward (`DELETE /demo/payment-behavior`).

**Real process-level recovery** (the part of this criterion that's new to Phase 3 — Phase 1/2 could
only illustrate this within one JVM):
1. Killed Inventory Service's process (`kill <pid>` on both the `mvn spring-boot:run` wrapper and
   the `java` process it launched — a real process termination, not a paused thread), confirmed
   `curl localhost:8082/...` no longer connects.
2. With Inventory Service down, placed an order: `POST localhost:8081/api/orders` for 1× `SKU-003`
   → `order-20004`, response `PENDING`. Order Service accepted it and published `OrderCreated`
   normally (Order Service has no synchronous dependency on Inventory Service — §4). Confirmed the
   order sat at `PENDING` (1-entry history) for several seconds — the `OrderCreated` record was
   durably persisted on the `orders.events` topic, unconsumed, since the only consumer group that
   would advance it (`inventory-service`) had no live members.
3. Restarted Inventory Service (`mvn spring-boot:run` again, same consumer group id
   `inventory-service`). On startup it rejoined the group, was assigned the same partitions, and
   resumed from its last committed offset — i.e., picked up the backlog rather than starting fresh.
4. Within ~1s of restart, `GET localhost:8081/api/orders/order-20004` showed the order had
   progressed all the way to `FULFILLED` (`PENDING -> INVENTORY_RESERVED -> PAYMENT_PENDING -> PAID
   -> FULFILLMENT_PENDING -> FULFILLED`) — the paused service's backlog was correctly processed
   once restarted, proving Kafka's durability/consumer-offset behavior is doing real work now that
   these are genuinely separate processes, not an in-process illustration.

**Reproduce:** `docker compose up -d`, then in four terminals `cd services/<name>-service && mvn
spring-boot:run` (with `JAVA_HOME` set as above), then the `curl` sequence above; to reproduce the
recovery step specifically, `kill` the inventory-service JVM mid-sequence and restart it with the
same command.

### "Service boundaries are understandable from directory structure alone"

`services/` contains exactly `common/`, `order-service/`, `inventory-service/`,
`payment-service/`, `fulfillment-service/` — four self-contained Spring Boot apps (own `pom.xml`,
own `src/main/java/com/orderfulfillment/<domain>/`, own `application.yml`, own
`db/migration/`, own `src/test/`) plus one shared library. Checked for cross-reaching:

```bash
grep -rn "^import com.orderfulfillment.monolith" services/*-service/src   # (none — package renamed)
# and, per service, confirmed no imports of another service's own package, only:
#   - com.orderfulfillment.common.* (the declared Maven dependency)
#   - its own com.orderfulfillment.<domain>.* package
```
No service directory contains a reference into another service's top-level directory or package.

### "Existing Phase 1/2 test coverage is preserved, split correctly per service"

Every unit test that existed in `services/monolith/src/test/` now lives in its owning service:
`OrderStatusTest`, `CreateOrderRequestValidationTest` → order-service;
`InventoryReservationExecutorTest`, `InventoryServiceOptimisticLockTest` → inventory-service;
`PaymentServiceTest` → payment-service. All ported with only the package statement changed (no
logic changes), and all still pass.

**Judgment call — the four whole-stack integration tests could not be ported as-is.**
`HappyPathIntegrationTest`, `OutOfStockIntegrationTest`, `PaymentRejectionIntegrationTest`, and
their shared `AbstractIntegrationTest` exercised all four domains in one JVM via one Testcontainers
Postgres+Kafka pair — a structure that stops being meaningful once the four domains are actually
four separate deployable services with no shared JVM to test inside. The prompt's own exit-criteria
text anticipates this directly: *"each service's integration test only needs to prove its own
consumers/producers behave correctly against the frozen contract, not stand up the other three
services."* Rewrote them as one `AbstractIntegrationTest` + one `<Service>ServiceIntegrationTest`
per service, each starting only that service (Testcontainers Postgres + Kafka, same pattern as
before) and simulating the other services' events by publishing them directly — using the service's
own `EventPublisher` bean (autowired into the test), so the wire format is byte-identical to what a
real upstream/downstream service would send, not a hand-rolled approximation:

- **order-service** (`OrderServiceIntegrationTest`, 5 tests): `POST /api/orders` → asserts
  `OrderCreated` published; injects `InventoryReserved`/`InventoryReservationFailed` →
  asserts the INVENTORY_RESERVED/PAYMENT_PENDING transition and `PaymentRequested` publish, or the
  REJECTED_OUT_OF_STOCK terminal; injects `PaymentAuthorized` then `ShipmentCreated` → asserts
  FULFILLED; injects `PaymentRejected` → asserts PAYMENT_FAILED. Covers
  `OrderInventoryEventsConsumer`, `OrderPaymentEventsConsumer`, `OrderFulfillmentEventsConsumer`,
  and `OrderService`'s own `OrderCreated` publish — Order Service's entire Kafka surface.
- **inventory-service** (`InventoryServiceIntegrationTest`, 3 tests): injects `OrderCreated` with
  adequate stock → asserts reservation + `InventoryReserved` publish; with excess quantity →
  asserts `InventoryReservationFailed`; injects `PaymentRejected` after a successful reservation →
  asserts release + `InventoryReleased` publish. Covers `InventoryOrderEventsConsumer` and
  `InventoryPaymentEventsConsumer`.
- **payment-service** (`PaymentServiceIntegrationTest`, 2 tests): injects `PaymentRequested` →
  asserts default-success `PaymentAuthorized`; arms `/demo/payment-behavior` REJECT then injects
  `PaymentRequested` → asserts `PaymentRejected` with the given reason. Covers
  `PaymentOrderEventsConsumer` and the demo control that drives it.
- **fulfillment-service** (`FulfillmentServiceIntegrationTest`, 1 test): injects `PaymentAuthorized`
  → asserts shipment creation + `ShipmentCreated` publish. Covers `FulfillmentPaymentEventsConsumer`.

Total: 31 tests across the four services (13 order, 10 inventory, 7 payment, 1 fulfillment),
all passing. Each proves its own service's consumers/producers against the frozen event-catalog
contract without standing up the other three — exactly what the exit criteria ask for — while §6's
manual multi-process walkthrough above is what proves the *actual* cross-process integration this
phase's exit criteria are ultimately about.

**Reproduce (per service, needs Docker running for Testcontainers):**
```bash
cd services/order-service && JAVA_HOME=<jdk21> mvn test        # 13 tests
cd services/inventory-service && JAVA_HOME=<jdk21> mvn test    # 10 tests
cd services/payment-service && JAVA_HOME=<jdk21> mvn test      # 7 tests
cd services/fulfillment-service && JAVA_HOME=<jdk21> mvn test  # 1 test
```

---

## 7. Retiring the monolith

Deleted only after all of §6 was verified against the new structure: `services/monolith/` (all
source, tests, `pom.xml`, `application.yml`, migration files, `.gitignore`) removed via
`git rm -r` + `rm -rf`. Re-ran `mvn -DskipTests package` from the repo root afterward to confirm
the five remaining modules build with no dangling reference to the deleted directory. Root
`.gitignore` gained `target/`, `*.log`, `.idea/`, `*.iml` (previously only
`services/monolith/.gitignore` had these — needed now that there are five Maven modules producing
build output instead of one).

---

## 8. Directory-ownership check (execution-plan.md §5 rule 4)

```bash
grep -rn "^import com.orderfulfillment" services/*/src | grep -v "com.orderfulfillment.common" \
  | awk -F: '{print $1}' | xargs -I{} dirname {} | sort -u
```
Confirms every non-`common` import inside a given service's source tree resolves to that same
service's own package — no service imports another service's package directly. `services/common`
is the only cross-service dependency, declared as an ordinary Maven module dependency in each
service's `pom.xml`, not a copy-pasted source tree.

---

## 9. Judgment calls (summary — reasoning is inline above where first relevant)

1. **Package naming** `com.orderfulfillment.<domain>` rather than `com.orderfulfillment.<domain>service`
   — §1.
2. **`services/common/` scope**: everything from the monolith's `common/` package moved except
   `SchemaMigrationRunner`/`SchemaMigrationJpaDependencyConfig`, which became unnecessary rather
   than shared — §2, §3.
3. **Integration tests rewritten per-service**, simulating upstream/downstream events via each
   service's own `EventPublisher` rather than porting the whole-stack tests unchanged (impossible
   once the domains are separate processes) or dropping them — §6.
4. **Root aggregator's `dependencyManagement`** centralizes only the Testcontainers BOM import;
   each service's `pom.xml` still declares its own `<dependency>` entries explicitly (not relying on
   inherited implicit versions beyond what `spring-boot-starter-parent` already manages) so that
   `services/<name>/pom.xml` remains readable on its own without requiring the reader to trace the
   full parent chain to know what's on the classpath.

---

## 10. Inconsistencies / gaps found in the frozen contracts or `docs/planning/`

1. **`docs/planning/execution-plan.md` §7's Flyway line has the same Boot-4-module-split gap the
   JPA and Kafka lines already got footnotes for** (`docs/agent-reports/phase-1.md` §1a,
   `docs/agent-reports/phase-2.md` §3.2) — see §3 above. `flyway-core` +
   `flyway-database-postgresql` alone do not activate Spring Boot's Flyway autoconfiguration under
   Boot 4.1; the actual autoconfiguration lives in `org.springframework.boot:spring-boot-flyway`,
   which must be an explicit dependency. Not previously surfaced because Phase 1/2's monolith never
   used Boot's Flyway autoconfiguration at all (it used the custom `SchemaMigrationRunner`, §2), so
   this gap only became visible now that Phase 3's genuine simplification (§3) actually exercises
   the built-in path for the first time. **Recommended fix:** add a parenthetical to
   `execution-plan.md` §7's Flyway line naming `spring-boot-flyway` as the Boot-4-appropriate module,
   matching the treatment the Boot 3→4 and Kafka-starter lines already get.
2. **No contract-level error** was found in `docs/openapi/*.yaml`, `docs/db-ownership.md`,
   `docs/events/event-catalog.md`, or `docs/order-state-machine.md` — all four were internally
   consistent with what Phase 1/2 had already built against them, and this phase changed no service
   behavior, only its process/module boundaries.
3. **Local dev data note, not a contract issue:** the shared `docker-compose.yml` Postgres volume
   (`orderfulfillment-postgres-data`) persisted across this session from earlier manual verification
   runs (Phase 2's own manual walkthrough also created an `order-20001`), and `IdGenerator`'s
   in-process counter always starts at `order-20001` after a fresh JVM start — so re-running the
   manual happy-path walkthrough against a *not-freshly-reset* volume produces a duplicate-key
   `500` on the first order, not a real bug. Verification in §6 used `docker compose down -v` before
   testing specifically to avoid this false signal; worth a one-line callout in whatever "local dev"
   documentation Phase 11 eventually writes, since a future reader hitting this by surprise could
   easily mistake it for an application bug.

---

## 11. Explicitly deferred to the follow-up fan-out stage

Per the prompt's own scoping and `docs/planning/execution-plan.md` §2's model/effort table, this
step is boundary-definition only. Left for the dedicated fan-out workstreams that follow:

- **Inventory Service's deeper concurrency-load verification (Scenario 7 — Inventory Contention).**
  This step only ported the existing Phase 1 concurrency unit test
  (`InventoryServiceOptimisticLockTest`, retry-then-succeed / retry-exhausted-then-throw against a
  mocked `ObjectOptimisticLockingFailureException`) into its new home in `services/inventory-service`
  unchanged and confirmed it still passes — it did **not** build new real-concurrent-HTTP-load test
  infrastructure against the now-standalone process, which `execution-plan.md` §2 flags as the
  single most correctness-sensitive piece of logic in the project and explicitly assigns to a later
  dedicated Opus/xhigh pass. The invariant ("total reserved inventory never exceeds available
  inventory" under genuine concurrent load against a real running Inventory Service) is not proven
  by anything in this report — only the pre-existing unit-level retry logic is.
- **Per-service refinement generally** — Order Service, Payment+Fulfillment Service becoming
  independent parallel workstreams per `execution-plan.md` §1.2/§4's Phase 3 fan-out row (deeper
  DTO/validation polish, anything beyond what Phase 1/2 already built and this step relocated
  unchanged).
- **Phase 4's reliability pattern** (idempotency table, retry/backoff, DLQ) is untouched by this
  step, as scoped — `docs/agent-reports/phase-2.md` §3.4 already documents the known
  `ConstraintViolationException` log-noise gap on Payment Service's `PROVIDER_ERROR` retries, which
  Phase 4's `processed_events` check is the actual fix for, not this phase.

---

## 12. Reproducing this phase from a clean clone

```bash
# Infrastructure
docker compose up -d
# -> postgres:16-alpine on 5432, apache/kafka:4.0.0 (KRaft) on 9092

# Backend — build everything once (installs services/common to the local repo so the other
# four modules can resolve it)
JAVA_HOME=<a JDK 21 install> mvn -DskipTests install

# Run all four services (four terminals, or four backgrounded processes)
cd services/order-service && JAVA_HOME=<jdk21> mvn spring-boot:run        # :8081
cd services/inventory-service && JAVA_HOME=<jdk21> mvn spring-boot:run    # :8082
cd services/payment-service && JAVA_HOME=<jdk21> mvn spring-boot:run      # :8083
cd services/fulfillment-service && JAVA_HOME=<jdk21> mvn spring-boot:run  # :8084

# Frontend (separate terminal)
cd frontend && npm install && npm run dev
# -> http://localhost:5173, calling Order Service (8081) and Inventory Service (8082) directly

# Tests (each spins up its own Testcontainers Postgres + Kafka, independent of docker-compose.yml
# and of the other three services)
cd services/order-service && JAVA_HOME=<jdk21> mvn test        # 13 tests
cd services/inventory-service && JAVA_HOME=<jdk21> mvn test    # 10 tests
cd services/payment-service && JAVA_HOME=<jdk21> mvn test      # 7 tests
cd services/fulfillment-service && JAVA_HOME=<jdk21> mvn test  # 1 test
```
