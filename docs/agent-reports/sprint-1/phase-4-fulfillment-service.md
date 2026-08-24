# Phase 4 Report — Fulfillment Service (fan-out step)

**Date:** 2026-08-18
**Scope:** apply the reliability pattern designed and reference-implemented against Inventory Service
(`docs/agent-reports/phase-4-pattern-design.md`, `docs/reliability-pattern.md`) to Fulfillment
Service — idempotent consumer, retry/backoff, DLQ routing, and real `/demo/consumers` pause/resume,
since Fulfillment Service's OpenAPI spec freezes that surface. Mechanical application only; no
pattern redesign.

**Result: 8 tests, 0 failures** in `services/fulfillment-service` (1 pre-existing +
7 new), green on two consecutive full-suite runs. `mvn -pl services/fulfillment-service -am clean
install` succeeds. No file outside `services/fulfillment-service/` was modified.

---

## 1. What changed

| File | Change |
|---|---|
| `db/migration/V2__processed_events.sql` | **New** — the ledger, copied from Inventory's `V4__processed_events.sql` shape exactly, in `fulfillment_service` schema. |
| `application.yml` | **New** — `orderfulfillment.reliability.processed-events-table: fulfillment_service.processed_events`. |
| `FulfillmentConsumers.java` | **New** — listener id (`payment-authorized`, matching the frozen OpenAPI example) and `processed_events.consumer_name` (`fulfillment.payment-authorized`), kept as two distinct namespaces per `docs/reliability-pattern.md` §2.4 rule 3. |
| `ShipmentCreationResult.java` | **New** — the `duplicate` outcome distinct from a normal creation, mirroring Inventory's `ReservationResult`. |
| `FulfillmentService.java` | `createShipment` now takes a `ProcessedEventKey`, calls `processedEventLedger.recordProcessed(eventKey)` as its first statement inside the `REQUIRES_NEW` transaction, and returns `ShipmentCreationResult.DUPLICATE` if the claim is lost. |
| `FulfillmentPaymentEventsConsumer.java` | `id = "payment-authorized"` on the listener; filter to `PaymentAuthorized` → `isProcessed()` early-out → pass `ProcessedEventKey` into `createShipment` → skip publish on `duplicate`. |
| `FulfillmentKafkaReliabilityConfig.java` | **New** — one `DefaultErrorHandler` bean, `ConsumerErrorHandlerFactory.create(KafkaTopics.FULFILLMENT_DLQ)`. |
| `DemoConsumerController.java` | **New** — copied verbatim from Inventory's, three one-line delegations to `ConsumerControl`. |
| `FulfillmentServiceApplication.java` | Added the `TypeExcludeFilter` `excludeFilters` fix to the hand-written `@ComponentScan`, per `phase-4-pattern-design.md` §4.1, before adding any test fixtures. |
| `AbstractIntegrationTest.java` | Added `KafkaTemplate<String,String>` and `JdbcClient` fields (mirroring Inventory's base test class) so the new poison/duplicate tests can publish raw bytes and read the ledger/shipments table directly. |
| 3 new test classes | See §3. |

Nothing in `services/common/` was touched — the ledger, error handler, backoff, DLQ headers and
pause/resume mechanism are all called, not reimplemented.

## 2. The defense-in-depth backstop

`shipments.order_id UNIQUE` (`V1__shipments.sql`, already present) relates to the ledger check the
same way the design report frames Payment Service's `idempotency_key UNIQUE`: the ledger key is the
Kafka `eventId`, so it is what stops a duplicate *delivery* of the same event; the unique constraint
is a business-level invariant ("one shipment per order") that would also catch a hypothetical bug
that reached `createShipment` twice for the same order under two *different* event ids. The ledger
is expected to be the one that actually fires in practice — the constraint is the backstop, not the
primary guard. This reasoning is recorded in `FulfillmentService.createShipment`'s Javadoc.

## 3. Tests, and how they verify Phase 4's exit criteria

All three run against real Testcontainers Kafka (`apache/kafka:4.0.0`) and Postgres
(`postgres:16-alpine`), no mocking of the mechanism, no hardcoded sleeps — every wait is Awaitility
or a bounded poll-until-found loop, matching `phase-3-inventory-concurrency.md` §5.3's standard.

- **`FulfillmentDuplicateEventIntegrationTest`** (2 tests) — republishes a byte-identical
  `PaymentAuthorized` envelope (same `eventId`) a second time to `payments.events`. Asserts exactly
  one `shipments` row for the order and exactly one `processed_events` row for
  `(eventId, "fulfillment.payment-authorized")`, with the negative proven via `await().during(5s)`
  rather than a single sample. The second test drains `fulfillment.events` for 15 seconds and counts
  `ShipmentCreated` records for the order, asserting exactly one — the downstream half of the
  duplicate-side-effect guarantee.
- **`FulfillmentConsumerOutageIntegrationTest`** (3 tests) — driven entirely through the real HTTP
  `/demo/consumers/payment-authorized/{pause,resume}` endpoints, not by reaching into the registry.
  Covers: `GET /demo/consumers` reporting the one listener correctly (name, topic, group, `paused`);
  pause → publish → assert **no shipment exists for a continuous 5-second window** → resume → assert
  the backlog drains to a shipment with `status == CREATED`; and the idempotent-pause/idempotent-resume
  /unknown-listener-404 case, matching Inventory's
  `pauseAndResumeAreIdempotentAndUnknownListenersAre404`. An unconditional `@AfterEach` resume prevents
  a mid-test failure from leaving the listener paused and breaking later tests.
- **`FulfillmentPoisonMessageIntegrationTest`** (2 tests) — publishes both poison forms
  `docs/scenarios.md` names (an `eventVersion: 99` envelope, and a valid envelope whose `payload` is
  a bare string) as real bytes to `payments.events`. Each asserts the record lands on
  `fulfillment.dlq` with the original key and bytes, `x-delivery-attempts: 1` (the honest count —
  proof the non-retryable classification held), `x-failure-retryable: false`, root-cause class and
  message, and the standard `kafka_dlt-*` metadata (original topic/partition/offset/consumer group,
  stack trace).

Full suite run twice consecutively: both runs report `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`,
`BUILD SUCCESS`. The pre-existing `FulfillmentServiceIntegrationTest` (1 test, unmodified) passes
unchanged in both runs.

```
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
mvn -pl services/fulfillment-service -am clean install -DskipTests
mvn -pl services/fulfillment-service test   # run 1: Tests run: 8, Failures: 0
mvn -pl services/fulfillment-service test   # run 2: Tests run: 8, Failures: 0
```

## 4. Where the shared pattern fit without adjustment

Everything applied cleanly: `ProcessedEventLedger`, `ConsumerErrorHandlerFactory`, `ConsumerControl`,
`ConsumerState`, `DlqHeaders`, `DeliveryAttemptTracker`, and `KafkaTopics.FULFILLMENT_DLQ` all
existed already and needed no changes. Fulfillment Service has exactly one `@KafkaListener`, so there
was no analogue of Inventory's "two consumers, two consumer_names" complexity — `FulfillmentConsumers`
holds one listener id and one consumer name instead of two of each, otherwise identical in shape and
purpose to `InventoryConsumers`.

No place in Fulfillment Service's code, schema, or OpenAPI contract required deviating from the
pattern, reimplementing shared machinery, or flagging a contract gap. No `docs/` file outside this
report was touched.
