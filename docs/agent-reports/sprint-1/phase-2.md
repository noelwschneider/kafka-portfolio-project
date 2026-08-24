# Phase 2 Report — Introduce Kafka (in-process)

**Date:** 2026-08-18
**Scope:** `docs/planning/implementation-phases.md`'s Phase 2 (Introduce Kafka) and
`docs/planning/execution-plan.md` §4's Phase 2 row.
**Output:** the same one-deployable-app `services/monolith/` from Phase 1, with every direct
in-process cross-domain call replaced by real Kafka producers/consumers driving the event flow in
`docs/events/event-catalog.md` §3. Still one Maven project, one Spring Boot app — service
extraction is Phase 3, not this phase.

No file under `docs/planning/`, `docs/openapi/`, `docs/order-state-machine.md`,
`docs/db-ownership.md`, `docs/scenarios.md`, `docs/adr/`, `docs/architecture-diagram.md`, or
`docs/events/event-catalog.md`'s existing content was modified. New files were added beside them
(`docs/events/schemas/*.json`), which is explicitly in scope per the catalog's own deferred-schema
note.

---

## 1. Files created / changed

### Backend (`services/monolith/`)

**New — envelope & payload types** (`common/events/`):
`EventEnvelope.java` (generic envelope record), `EventItem.java`, `ShortageItem.java`, and one
record per catalogued payload: `OrderCreatedPayload`, `InventoryReservedPayload`,
`InventoryReservationFailedPayload`, `InventoryReleasedPayload`, `PaymentRequestedPayload`,
`PaymentAuthorizedPayload`, `PaymentRejectedPayload`, `ShipmentCreatedPayload`.

**New — Kafka infrastructure** (`common/kafka/`):
`KafkaTopics.java` (topic-name constants), `EventTypes.java` (eventType strings + the frozen
`CURRENT_VERSION = 1`), `KafkaTopicConfig.java` (`NewTopic` beans, 3 partitions/1 replica per
topic), `EventPublisher.java` (wraps a payload in the envelope, reads `correlationId` from
`CorrelationIdHolder`, sends via `KafkaTemplate<String,String>` keyed on `aggregateId`),
`EventCodec.java` (decodes the envelope with `payload` left as `JsonNode` until `eventType` is
known, then converts; enforces the `eventVersion` check), `UnsupportedEventVersionException.java`.

**New — Kafka consumers**, one class per (service, topic) pair, living in the domain package they
belong to (not a subpackage — `OrderPersistence`/`InventoryReservationExecutor` are
package-private, so consumers that call them must share the package):
- `order/OrderInventoryEventsConsumer.java` — `inventory.events` → `InventoryReserved`/
  `InventoryReservationFailed`; on success, also publishes `PaymentRequested`.
- `order/OrderPaymentEventsConsumer.java` — `payments.events` → `PaymentAuthorized`/`PaymentRejected`.
- `order/OrderFulfillmentEventsConsumer.java` — `fulfillment.events` → `ShipmentCreated`.
- `inventory/InventoryOrderEventsConsumer.java` — `orders.events` → `OrderCreated` (reserve, publish
  `InventoryReserved`/`InventoryReservationFailed`).
- `inventory/InventoryPaymentEventsConsumer.java` — `payments.events` → `PaymentRejected`
  (compensation: release, publish `InventoryReleased`).
- `payment/PaymentOrderEventsConsumer.java` — `orders.events` → `PaymentRequested` (authorize,
  publish `PaymentAuthorized`/`PaymentRejected`).
- `fulfillment/FulfillmentPaymentEventsConsumer.java` — `payments.events` → `PaymentAuthorized`
  (independent fan-out consumer, own consumer group; creates shipment, publishes `ShipmentCreated`).

**New — supporting types:**
`inventory/ReleaseResult.java` (return type for `InventoryService.release`, carrying the released
lines + shared reservation-group id so the compensation event can be published with real data),
`payment/PaymentProviderException.java` (see §3.4 below).

**Changed:**
- `order/OrderService.java` — `createOrder` no longer calls Inventory/Payment/Fulfillment directly;
  it persists `PENDING`, publishes `OrderCreated`, and returns. `runWorkflow` and its
  `PROVIDER_ERROR`→`FAILED` mapping are gone.
- `inventory/InventoryService.java` / `InventoryReservationExecutor.java` — `release()` now returns
  `ReleaseResult` instead of `void`, so the caller (the new Kafka consumer) can publish
  `InventoryReleased` with the actual reservationId/items rather than reconstructing them.
- `common/CorrelationIdHolder.java` — added `runInScope(UUID, Runnable)`, the Kafka-consumer-thread
  counterpart to what `CorrelationIdFilter` already does per HTTP request; every `@KafkaListener`
  wraps its handling in this so `EventPublisher` (which reads the holder rather than taking an
  explicit parameter) and log lines pick up the correlationId carried on the envelope being
  consumed.
- `pom.xml` — added `spring-boot-starter-kafka` (not raw `spring-kafka` — see §3.2),
  `spring-kafka-test`, `org.testcontainers:kafka`, `org.awaitility:awaitility` (test scope).
- `application.yml` — `spring.kafka.*` (bootstrap-servers, String (de)serializers, `auto-offset-
  reset: earliest`), `spring.jackson.deserialization.fail-on-unknown-properties: false` (the
  envelope-versioning rule in event-catalog.md §5 requires consumers to ignore unknown fields).

### Contracts (new files only — `docs/events/event-catalog.md` itself untouched)

`docs/events/schemas/*.json` — **8** JSON Schema files (see §3.1 on why 8, not the prompt's stated
7): `OrderCreated`, `InventoryReserved`, `InventoryReservationFailed`, `InventoryReleased`,
`PaymentRequested`, `PaymentAuthorized`, `PaymentRejected`, `ShipmentCreated`. Each covers only the
envelope's `payload` field (not the envelope itself, which is shared/already documented in
event-catalog.md §1), matching the documented field names/types/enums exactly.

### Infrastructure

`docker-compose.yml` (new, repo root) — `postgres:16-alpine` + `apache/kafka:4.0.0` (KRaft,
single-node broker+controller), per the pinned-tech table. Phase 1 left no compose file to extend
(it ran Postgres via a bare `docker run`), so this is a new mechanism, not a second one competing
with an existing file.

### Frontend

- `frontend/src/pages/OrderDetailPage.tsx` — poll interval changed from a flat 4s to a
  status-aware 1s-while-non-terminal / stop-when-terminal function, and the comment rewritten:
  this poll is now what actually surfaces the Phase-2 async transitions, not just a demo-recovery
  convenience.
- `frontend/src/App.tsx` — header subtitle updated from "Phase 1 — modular monolith, synchronous
  workflow" to "Phase 2 — modular monolith, Kafka-driven workflow" (was stale/inaccurate the moment
  this phase landed).

### Tests

- `AbstractIntegrationTest.java` — added a singleton `org.testcontainers.kafka.KafkaContainer`
  (the native-KRaft module, matching `apache/kafka:4.0.0`) alongside the existing singleton
  Postgres container, wired via `@DynamicPropertySource`.
- `HappyPathIntegrationTest.java`, `OutOfStockIntegrationTest.java`,
  `PaymentRejectionIntegrationTest.java` — rewritten to assert the immediate response is `PENDING`
  and then use Awaitility (`await().atMost(20s).untilAsserted(...)`) to poll `GET
  /api/orders/{id}` for the eventual terminal state, since the workflow is now asynchronous.
  `HappyPathIntegrationTest` adds the explicit timing/ordering proof (see §4).
- `InventoryReservationExecutorTest.java` — extended to assert `release()`'s new `ReleaseResult`
  return value (reservationId + items), plus a new case for releasing an order with no
  reservations.

---

## 2. The event flow, as actually wired

```
POST /api/orders
  -> OrderService persists PENDING, EventPublisher publishes OrderCreated (orders.events, key=orderId)

InventoryOrderEventsConsumer (group inventory-service) consumes orders.events
  -> filters to OrderCreated; InventoryService.reserve(...)
  -> publishes InventoryReserved or InventoryReservationFailed (inventory.events)

OrderInventoryEventsConsumer (group order-service) consumes inventory.events
  -> InventoryReserved: appendStatus(INVENTORY_RESERVED, eventId), appendStatus(PAYMENT_PENDING, null),
     publishes PaymentRequested (orders.events) with idempotencyKey = this event's own eventId
  -> InventoryReservationFailed: appendStatus(REJECTED_OUT_OF_STOCK, eventId) [terminal]

PaymentOrderEventsConsumer (group payment-service) consumes orders.events
  -> filters to PaymentRequested; PaymentService.authorize(...)
  -> AUTHORIZED -> publishes PaymentAuthorized (payments.events)
  -> REJECTED   -> publishes PaymentRejected (payments.events)
  -> PROVIDER_ERROR -> throws (see §3.4) — no event published, order stays PAYMENT_PENDING

OrderPaymentEventsConsumer (group order-service) consumes payments.events
  -> PaymentAuthorized: appendStatus(PAID, eventId), appendStatus(FULFILLMENT_PENDING, null)
  -> PaymentRejected: appendStatus(PAYMENT_FAILED, eventId) [terminal]

FulfillmentPaymentEventsConsumer (group fulfillment-service) consumes payments.events INDEPENDENTLY
  -> filters to PaymentAuthorized; FulfillmentService.createShipment(...)
  -> publishes ShipmentCreated (fulfillment.events)

OrderFulfillmentEventsConsumer (group order-service) consumes fulfillment.events
  -> ShipmentCreated: appendStatus(FULFILLED, eventId) [terminal]

InventoryPaymentEventsConsumer (group inventory-service) consumes payments.events
  -> filters to PaymentRejected; InventoryService.release(...) [compensation]
  -> publishes InventoryReleased (inventory.events) — no consumer in v1, per the catalog
```

Five consumer groups total: `order-service` (3 listeners, 3 topics), `inventory-service` (2
listeners, 2 topics), `payment-service` (1 listener), `fulfillment-service` (1 listener) — matching
the catalog's one deliberate fan-out (`PaymentAuthorized` read independently by `order-service` and
`fulfillment-service`).

---

## 3. Judgment calls

### 3.1 Added an 8th JSON Schema file (`InventoryReleased`), not just the 7 the prompt named

The prompt's file list ("7 files: OrderCreated, InventoryReserved, InventoryReservationFailed,
PaymentRequested, PaymentAuthorized, PaymentRejected, ShipmentCreated") omits
`InventoryReleased`, even though `docs/events/event-catalog.md` §3 catalogs it as an 8th real event
type with its own documented payload. Since the frozen catalog is the authority this prompt itself
points to ("matching each event's documented payload fields/types exactly"), and the catalog lists
8 events, I added `InventoryReleased.schema.json` too rather than leaving a real event without a
schema. Flagged here rather than silently deviating from the prompt's literal count.

### 3.2 `spring-boot-starter-kafka`, not raw `spring-kafka`

Spring Boot 4 split `spring-boot-autoconfigure` into many small per-concern modules (already
established in Phase 1's report for JPA). The same is true for Kafka: adding the raw
`org.springframework.kafka:spring-kafka` dependency compiles fine but does **not** pull in
`spring-boot-kafka` (the autoconfiguration module that defines the `KafkaTemplate<String,String>`
bean, `NewTopic`-driven `KafkaAdmin`, and `@KafkaListener` container factory) — every `@KafkaListener`
constructor-injecting `KafkaTemplate<String,String>` failed at context startup with
`NoSuchBeanDefinitionException`. Fixed by depending on `org.springframework.boot:spring-boot-
starter-kafka` (which exists as a real Maven Central artifact under the 4.1.0 BOM), matching the
pattern Boot 4's other starters use. `execution-plan.md` §7's "Spring for Apache Kafka
(`spring-kafka`)" line names the library, not the Maven coordinate to depend on directly under
Boot 4's split; worth a note for whoever next edits that table.

### 3.3 Jackson 3 (`tools.jackson.*`), not `com.fasterxml.jackson.databind`/`.core`

Spring Boot 4.1.0's baseline is Jackson 3, which renamed the databind/core packages from
`com.fasterxml.jackson.{databind,core}` to `tools.jackson.{databind,core}` (annotations stayed at
`com.fasterxml.jackson.annotation`). `EventCodec`/`EventPublisher`/`EventEnvelope` and every
consumer's `JsonNode` import use the new namespace. One functional consequence: Jackson 3's
`ObjectMapper` read/write methods throw the unchecked `tools.jackson.core.JacksonException` instead
of the old checked `JsonProcessingException`, so no try/catch is needed around
`readValue`/`writeValueAsString`/`treeToValue` — a let-it-fail-loudly default that happens to match
this phase's "no idempotency, no DLQ, let it fail loudly" rule for free. Not previously documented
anywhere (Phase 1 didn't touch Jackson directly); recording it here since it will matter to Phase 3+
agents writing any other JSON-touching code against this Boot 4.1.0 baseline.

### 3.4 `PaymentOutcome.Kind.PROVIDER_ERROR` now throws (`PaymentProviderException`), not a manufactured `FAILED` transition

Phase 1's synchronous code (documented in phase-1.md §3.6) mapped this simulated mode onto the
`FAILED` terminal transition as a stopgap, since no retry/DLQ machinery existed to give it real
behavior. `docs/events/event-catalog.md` §3's `PaymentRejected` note is explicit about what this
mode should actually do: "raises inside the Payment Service consumer, is retried with backoff, and
lands in `payments.dlq` if attempts are exhausted... It never surfaces as `PaymentRejected`, and it
leaves the order in `PAYMENT_PENDING`." Phase 2 still has no retry/backoff/DLQ (Phase 4), so rather
than keep Phase 1's improvised `FAILED` mapping (which no longer has a call site to live in, now
that `OrderService` doesn't orchestrate payment itself), `PaymentOrderEventsConsumer` throws
`PaymentProviderException` and lets it propagate uncaught. This is closer to the catalog's actual
described behavior than Phase 1's version was: the order genuinely stays in `PAYMENT_PENDING`
(verified manually — see §5), and Spring Kafka's default listener error handler retries the record
a bounded number of times before giving up and moving the offset forward (no infinite loop, no
silent swallow) — which is the honest "let it fail loudly" outcome this phase's rules call for
in the no-DLQ interim. **Known side effect, not fixed this phase:** because `PaymentService.authorize`
persists the `PaymentAttemptEntity` row (status `PENDING`) *before* returning the `PROVIDER_ERROR`
outcome, each of those bounded in-container retries re-invokes `authorize` with the same
`idempotencyKey` and hits `payment_attempts.idempotency_key`'s unique constraint on the 2nd+ attempt
— logged as a `ConstraintViolationException` per retry. The *end state* is correct
(`PAYMENT_PENDING`, one attempt row, order recoverable once Phase 4 adds idempotent redelivery
handling), but the log noise is a direct, expected consequence of this phase's explicit "no
idempotency yet" scope, not a bug introduced here. Phase 4's `processed_events` check is the actual
fix.

### 3.5 `EventCodec` decodes to `EventEnvelope<JsonNode>`, converting to a concrete payload type only after `eventType` is known

Several topics carry more than one event type (`orders.events`: `OrderCreated` +
`PaymentRequested`; `payments.events`: `PaymentAuthorized` + `PaymentRejected`), so a consumer can't
know the payload's concrete type until it has read `eventType`. Rather than a second envelope class,
`EventEnvelope<T>` stays generic and `EventCodec.decode` always deserializes with `T = JsonNode`;
`payloadAs(envelope, SomeType.class)` does the second-stage conversion once the caller's `switch` has
picked a branch.

### 3.6 `CorrelationIdHolder.runInScope`, extending the existing HTTP mechanism rather than building a second one

Per the prompt's explicit instruction to reuse Phase 1's correlation-id plumbing rather than invent
a parallel mechanism for Kafka: `EventPublisher` always reads `CorrelationIdHolder.get()` (never
takes an explicit `correlationId` parameter), exactly like `OrderController`'s HTTP path already
implicitly relies on `CorrelationIdFilter` having set it. Every `@KafkaListener` method's first line
is `CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope))`, the
consumer-thread counterpart to what the filter does per HTTP request (set on the way in, MDC-tagged
for logs, cleared in `finally`). This keeps "how does the currently-publishing code know its
correlationId" a single answer everywhere, HTTP or Kafka.

### 3.7 Local Kafka image pinned to `apache/kafka:4.0.0`, not `:3.9.0`

Attempted `apache/kafka:3.9.0` first (a reasonable, currently-available pinned tag under the
project's "apache/kafka Docker image, KRaft mode" rule). It failed to start under both plain Docker
Compose invocation patterns *and* Testcontainers 1.21.4's native-KRaft `KafkaContainer` module with
a hard, reproducible error: `advertised.listeners cannot use the nonroutable meta-address 0.0.0.0`,
even when the advertised address was independently confirmed correct (`localhost:<port>`) via a
standalone repro harness. `apache/kafka:4.0.0` starts cleanly with the identical
Testcontainers/Compose configuration otherwise unchanged. This looks like a genuine incompatibility
between that specific image tag's `/etc/kafka/docker/run` entrypoint script and this environment
(Docker Desktop for Mac's socket/host-resolution setup), not anything in this project's code —
recorded here since it's the kind of thing that will silently "just work" or "just fail" differently
on a different machine/CI runner. Both `docker-compose.yml` and
`AbstractIntegrationTest`'s Testcontainers `KafkaContainer` use `apache/kafka:4.0.0`. No document
pins an exact patch version, so this is not a contract deviation — just recording the concrete
choice and why, for whoever next touches Kafka image version pins (e.g. Phase 7's
containerization work).

### 3.8 `InventoryReservationExecutor.release` returns `ReleaseResult` instead of `void`

Needed so `InventoryPaymentEventsConsumer` can publish `InventoryReleased` with the real
`reservationId` and `items[]` (event-catalog.md §3's documented payload) rather than either
omitting them or re-deriving them from a second query. The reservation-group id is recovered by
stripping the known `-<sku>` suffix off each per-line reservation row's id (the same convention
`InventoryReservationExecutor.attemptReserve` already used to build those ids in Phase 1) —
no schema change, no new column.

---

## 4. Exit criteria — verified, with reproduction steps

### "Happy-path order fully travels through Kafka ... and reaches FULFILLED"

- **Automated:** `HappyPathIntegrationTest` (Testcontainers Postgres + Kafka) places an order,
  asserts the immediate response is `PENDING`, awaits (Awaitility, 20s timeout) `GET
  /api/orders/{id}` reaching `FULFILLED`, then asserts the full 6-entry status history, the payment
  attempt is `AUTHORIZED`, a shipment exists, and `inventory_items.reserved_quantity` increased by
  exactly 1 — same assertions as Phase 1's version, now reached asynchronously.
- **Manual, against the real running stack:** `docker compose up -d` (real `apache/kafka:4.0.0` +
  `postgres:16-alpine`), `mvn spring-boot:run`, then `curl -X POST /api/orders` for 1× SKU-001.
  Response: `{"id":"order-20001","status":"PENDING",...}`. Polling `GET /api/orders/order-20001`
  ~350ms later showed the full transition sequence `PENDING → INVENTORY_RESERVED →
  PAYMENT_PENDING → PAID → FULFILLMENT_PENDING → FULFILLED`, each history entry carrying the
  Kafka-consumed event's real `eventId` as `sourceEventId` (except the internal transitions, which
  are `null`, per `docs/order-state-machine.md` §3). Application logs during startup confirm 5
  distinct consumer groups (`order-service` ×3 listeners, `inventory-service` ×2,
  `payment-service` ×1, `fulfillment-service` ×1) each independently assigned partitions on the
  four topics — i.e., these are real subscriptions, not a stub.
- **Reproduce:**
  ```bash
  cd services/monolith && JAVA_HOME=<a JDK 21 install> mvn test -Dtest=HappyPathIntegrationTest
  ```

### "POST /api/orders returns PENDING and returns before fulfillment completes — verified with an actual timing/ordering check"

- The response body assertion (`accepted.status()` equals `"PENDING"`) is necessary but not
  sufficient on its own, so `HappyPathIntegrationTest` also captures `Instant responseReceived =
  Instant.now()` immediately after the HTTP response arrives, and later asserts
  `shipment.createdAt().isAfter(responseReceived)` — i.e., the shipment (the last thing the
  happy path creates) is provably timestamped *after* the client had already received the HTTP
  response, not just after the request logically started. (An earlier version of this test tried
  asserting `GET /api/shipments/{id}` returns 404 immediately after the POST response — that was
  **flaky**, since this test environment's local Kafka/Postgres round trips are fast enough that the
  whole workflow can complete before the very next HTTP call executes; the timestamp comparison is
  the actual non-racy proof and is what's committed.)
- **Reproduce:** same command as above; the timing assertion is part of the same test method.

### "The UI observably shows the order's status changing after the initial response, without a page reload"

- **Manual, via the Browser tool against the real running stack:** armed
  `PUT /demo/payment-behavior {"mode":"RETRYABLE_ERROR"}` (so the order pauses at `PAYMENT_PENDING`
  long enough to observe, rather than racing through to a terminal state in under a second), created
  an order for SKU-003 through the actual frontend UI. The order-detail page navigated to
  immediately showing `PENDING`; a screenshot ~2s later, taken on the same page with no navigation/
  reload, showed the status badge as `PAYMENT_PENDING` and a 3-entry status history (`PENDING →
  INVENTORY_RESERVED → PAYMENT_PENDING`) rendered live via the `refetchInterval` poll added to
  `OrderDetailPage.tsx` (1s while non-terminal). Reset the payment-behavior override afterward.
- `OrderDetailPage.tsx`'s poll now stops once the order reaches a terminal status (checked against
  `docs/order-state-machine.md` §1's terminal set), rather than polling forever — a small
  correctness improvement over Phase 1's flat unconditional 4s interval, not itself an exit
  criterion but worth noting.

### "Out-of-stock and payment-rejection paths still reach their Phase 1 terminal states and still release inventory on payment failure — now via the Kafka compensation hop"

- **Automated:** `OutOfStockIntegrationTest` and `PaymentRejectionIntegrationTest`, both rewritten
  to assert the immediate `PENDING` response and then `await()` the eventual terminal state.
  `PaymentRejectionIntegrationTest` additionally polls (rather than asserting immediately) for
  `inventory_items.reserved_quantity` returning to its pre-order value, since the compensation hop
  (`InventoryPaymentEventsConsumer` reacting to `PaymentRejected`) is itself asynchronous.
- **Manual:** reproduced both paths via `curl` against the running stack (§5 has the exact
  commands/output) — out-of-stock returned `PENDING` then settled on `REJECTED_OUT_OF_STOCK` with a
  2-entry history and unchanged inventory; the payment-rejection path returned `PENDING`, settled on
  `PAYMENT_FAILED` with a 4-entry history, and `SKU-002`'s `reservedQuantity` was confirmed back at
  its pre-order value (read from the API before and after, not assumed).
- **Reproduce:**
  ```bash
  mvn test -Dtest=OutOfStockIntegrationTest
  mvn test -Dtest=PaymentRejectionIntegrationTest
  ```

### "Existing Phase 1 unit tests still pass; integration tests exercise the Kafka-mediated flow for the happy path and both failure paths"

```
$ mvn test
...
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
23 tests total: the same 14 Phase 1 unit tests (domain rules, state machine, reservation executor,
optimistic-lock retry, payment simulator — all untouched by this phase, since none of them exercise
the removed synchronous orchestration path), 2 new assertions inside the extended
`InventoryReservationExecutorTest`, and the 3 rewritten Testcontainers integration tests (one per
required path), now backed by a real Postgres **and** a real Kafka broker per test class.
```bash
cd services/monolith
JAVA_HOME=<a JDK 21 install> mvn test
```

---

## 5. Manual end-to-end verification transcript (abbreviated)

```bash
$ docker compose up -d
$ mvn spring-boot:run   # picks up 5 consumer groups on startup, confirmed in logs

$ curl -s -X POST localhost:8080/api/orders -d '{"customerId":"demo-customer","items":[{"sku":"SKU-001","quantity":1}]}'
{"id":"order-20001","status":"PENDING","createdAt":"2026-08-18T00:12:43.442173Z"}

$ sleep 3 && curl -s localhost:8080/api/orders/order-20001 | jq .status
"FULFILLED"    # full 6-entry history, real sourceEventId per event-caused transition

$ curl -s -X POST localhost:8080/api/orders -d '{"customerId":"demo-customer","items":[{"sku":"SKU-004","quantity":10}]}'
{"id":"order-20002","status":"PENDING",...}
$ curl -s localhost:8080/api/orders/order-20002 | jq .status
"REJECTED_OUT_OF_STOCK"

$ curl -X PUT localhost:8080/demo/payment-behavior -d '{"mode":"REJECT","failureReason":"CARD_DECLINED"}'
$ curl -s -X POST localhost:8080/api/orders -d '{"customerId":"demo-customer","items":[{"sku":"SKU-002","quantity":1}]}'
{"id":"order-20003","status":"PENDING",...}
$ curl -s localhost:8080/api/orders/order-20003 | jq .status
"PAYMENT_FAILED"     # SKU-002 reservedQuantity confirmed back to 0 (its pre-order value)
```

---

## 6. Inconsistencies / gaps found in the frozen contracts or `docs/planning/`

1. **Prompt's "7 files" for `docs/events/schemas/` undercounts the catalog by one** — see §3.1.
   `docs/events/event-catalog.md` §3 lists 8 event types (`InventoryReleased` included); the prompt
   enumerated only 7, omitting it. Not a contract *error* (the catalog itself is internally
   consistent and complete), just a gap between this phase's instructions and the catalog they
   point to as authoritative. Resolved by following the catalog (8 schemas), flagged rather than
   silently picking one interpretation.
2. **`docs/planning/execution-plan.md` §7's "Spring for Apache Kafka (`spring-kafka`)" line needs a
   footnote for Boot 4** — see §3.2. The library name is right; the Maven coordinate to actually
   depend on under Boot 4's modularized autoconfiguration is `spring-boot-starter-kafka`, not the
   bare `spring-kafka` artifact, mirroring the JPA/EntityManagerFactory package-split note Phase 1
   already left in `docs/agent-reports/phase-1.md` §1a for the same underlying reason (Boot 4 split
   `spring-boot-autoconfigure` into many small per-concern modules). Recommended fix for whoever
   next touches that file: add a parenthetical noting the Boot-4-appropriate starter artifact, same
   treatment as the Spring Boot 3→4 line already gets.
3. **No pinned Kafka image tag anywhere in `docs/planning/`** — `project-overview.md`'s
   Pinned Technology Decisions table says "`apache/kafka` Docker image (KRaft mode, no ZooKeeper)"
   without a version. That's not wrong, but §3.7 above is worth a read for whoever picks the tag
   next (Phase 7's containerization, most likely): `apache/kafka:3.9.0` did not work in this
   environment/Testcontainers combination, `apache/kafka:4.0.0` did. Not proposing a pin be added to
   a frozen doc; recording the concrete finding here per the coordination protocol so it isn't
   silently rediscovered.

None of the above required a change to any frozen contract file's content.

---

## 7. Reproducing this phase from a clean clone

```bash
# Infrastructure
docker compose up -d
# -> postgres:16-alpine on 5432, apache/kafka:4.0.0 (KRaft) on 9092

# Backend
cd services/monolith
JAVA_HOME=<a JDK 21 install> mvn spring-boot:run
# -> http://localhost:8080, 4 topics auto-declared (3 partitions each), 5 consumer groups start

# Frontend (separate terminal)
cd frontend
npm install
npm run dev
# -> http://localhost:5173 — create an order, watch its status change live on the detail page

# Tests (spins up its own Testcontainers Postgres + Kafka, independent of docker-compose.yml)
cd services/monolith
JAVA_HOME=<a JDK 21 install> mvn test
```
