# Phase 4 Report — Order Service (fan-out step)

**Date:** 2026-08-18
**Scope:** apply the reliability pattern designed once against Inventory Service
(`docs/reliability-pattern.md`, `docs/agent-reports/phase-4-pattern-design.md`) to Order Service —
idempotent consumers, the shared retry/backoff error handler, and DLQ routing. Mechanical
application, not a design task; nothing in `services/common/` was changed.

**Result:** 17 tests, 0 failures, green on two consecutive full-suite runs of
`services/order-service` (plus `services/common`, on which it depends). No test outside
`services/order-service/` was touched or affected.

---

## 1. What changed

All changes are confined to `services/order-service/`, per the brief's isolation rule.

| File | What |
|---|---|
| `src/main/resources/db/migration/V2__processed_events.sql` | New. Exact copy of Inventory Service's `V4__processed_events.sql` shape, in `order_service` schema. |
| `src/main/resources/application.yml` | Added `orderfulfillment.reliability.processed-events-table: order_service.processed_events`. |
| `OrderConsumers.java` | New. Three listener ids (`inventory-events`, `payment-events`, `fulfillment-events`) and three `processed_events.consumer_name` values (`order.inventory-events`, `order.payment-events`, `order.fulfillment-events`), mirroring `InventoryConsumers`. Order Service has no `/demo/consumers` in its OpenAPI document, so the listener ids have no frozen contract to match — they exist for logging/consistency only, as the brief specifies. |
| `StatusTransitionResult.java` | New. A `duplicate` outcome distinct from "applied", mirroring `ReservationResult`/`ReleaseResult`. Carries `totalAmount` for the one transition whose caller needs data back out of the transaction. |
| `OrderPersistence.java` | Five methods now, all `@Transactional(REQUIRES_NEW)`: `createPendingOrder` (unchanged), `appendStatus` (single-transition case: InventoryReservationFailed, PaymentRejected, ShipmentCreated), `appendInventoryReservedTransition` (transitions 2+4 in one transaction), `appendPaymentAuthorizedTransition` (transitions 5+7 in one transaction). Each of the four status-writing methods takes a `ProcessedEventKey` and claims it via `processedEventLedger.recordProcessed()` as its first statement, exactly as `InventoryReservationExecutor.attemptReserve` does. |
| `OrderInventoryEventsConsumer.java`, `OrderPaymentEventsConsumer.java`, `OrderFulfillmentEventsConsumer.java` | Each now: filters to the event type(s) it handles (already true before this step, via the `switch`/`if`) → `isProcessed()` early-out with a log line → passes the `ProcessedEventKey` into the `OrderPersistence` method that owns the transaction → skips publishing on `duplicate()`. |
| `OrderKafkaReliabilityConfig.java` | New. One `@Configuration`, one `DefaultErrorHandler` bean via `ConsumerErrorHandlerFactory.create(KafkaTopics.ORDERS_DLQ)` — the whole of Order Service's share of the retry/DLQ policy, matching the reference implementation's shape exactly. |
| `OrderServiceApplication.java` | Applied the §4.1 `TypeExcludeFilter` fix to the hand-written `@ComponentScan`, before adding any test fixture. |
| `AbstractIntegrationTest.java` (test) | Added `kafkaTemplate` and `jdbcClient` `@Autowired` fields, matching Inventory Service's base test class — needed to publish genuinely malformed records and to read the ledger directly. |
| `OrderDuplicateEventIntegrationTest.java`, `OrderPoisonMessageIntegrationTest.java` | New, described below. |

Nothing in `services/common/` was touched. No ledger, error handler, backoff, or DLQ-header logic
was reimplemented locally.

## 2. Order Service's shape differs from Inventory's in two ways worth naming

**Three listeners, not two**, one per inbound topic (`inventory.events`, `payments.events`,
`fulfillment.events`). Each still uses exactly one `consumer_name` per listener method even though
two of the three switch on multiple event types — `InventoryReserved`/`InventoryReservationFailed`
on the first, `PaymentAuthorized`/`PaymentRejected` on the second — per the brief and
`docs/reliability-pattern.md` §8 point 3. The composite `(event_id, consumer_name)` key already
disambiguates by event; splitting the name per event type would buy nothing and would only make it
easier for the fulfillment listener's future second event type (if one is ever added) to drift from
this convention.

**Two of Order Service's three listeners drive *two* `order_status_history` rows from one inbound
event**, not one, because of the internal transitions `docs/order-state-machine.md` names (4:
`INVENTORY_RESERVED`→`PAYMENT_PENDING`, and 7: `PAID`→`FULFILLMENT_PENDING`). Inventory Service's
reference implementation has no analogous case — its two listeners each write exactly one outcome.
This is the one place the pattern needed a genuine (not cosmetic) decision: **the ledger claim has
to cover both writes**, so `appendInventoryReservedTransition` and
`appendPaymentAuthorizedTransition` each do the `recordProcessed()` claim once, then both `writeStatus`
calls, inside one `REQUIRES_NEW` transaction. This is not a divergence from the pattern — it is the
same rule (§2.3: "the claim must go in the innermost method that *is* the business transaction") applied
to a business transaction that happens to write two rows instead of one. `OrderDuplicateEventIntegrationTest`
exists specifically to prove this pairing holds under a real duplicate delivery (see §3).

No other divergence from the shared pattern was needed. `KafkaTopics.ORDERS_DLQ` was used as-is; no
backoff numbers or exception classification were touched locally.

## 3. How the tests verify Phase 4's exit criteria

Both classes are real Testcontainers Kafka (`apache/kafka:4.0.0`) + Postgres (`postgres:16-alpine`)
integration tests, no mocking of the mechanism, no hardcoded sleeps — every wait is Awaitility or a
bounded poll-until-found loop, matching `docs/agent-reports/phase-3-inventory-concurrency.md` §5.3.

**`OrderDuplicateEventIntegrationTest`** (2 tests) — republishes a real `InventoryReserved` record on
`inventory.events` with an identical `eventId` (byte-identical envelope, sent twice through
`EventPublisher`, the same bean production code uses, so the wire format matches a real upstream
service exactly):

- `republishingTheSameInventoryReservedEventAppliesTheTransitionOnce` — after the second delivery:
  exactly the three expected `order_status_history` rows (`PENDING`, `INVENTORY_RESERVED`,
  `PAYMENT_PENDING`), not five, and exactly one `processed_events` row for
  `(eventId, "order.inventory-events")`. The negative is asserted with `await().during(5s)` so a
  late-arriving second pair of rows still fails the test — not a single sample.
- `theDuplicateDoesNotRepublishPaymentRequested` — counts `PaymentRequested` records on
  `orders.events` keyed to this order over a bounded 15s window after the duplicate delivery and
  asserts exactly one, proving the downstream half of the duplicate side effect doesn't happen
  either.

**`OrderPoisonMessageIntegrationTest`** (2 tests) — publishes genuinely unprocessable bytes to
`inventory.events`, matching Inventory Service's two poison forms:

- an envelope with `eventVersion: 99` (unsupported version) — dead-lettered via
  `UnsupportedEventVersionException`, `x-delivery-attempts: 1`, `x-failure-retryable: false`.
- a structurally valid envelope whose `payload` is a bare string instead of an `InventoryReserved`
  object — fails in `payloadAs()`, dead-lettered via a `tools.jackson.*` exception, same honest
  `x-delivery-attempts: 1`.

Both assert the full metadata set: original topic/partition/offset/consumer-group headers,
`x-failure-class`/`x-failure-message` naming the *root cause* (not the
`ListenerExecutionFailedException` wrapper), `x-dead-lettered-at`, and that the DLQ record's value
is byte-identical to what was published (so a corrected replay is possible). Both land on
`orders.dlq`, confirming `KafkaTopics.ORDERS_DLQ` is wired correctly regardless of which topic
(`inventory.events` here) the poison record arrived on.

No outage/`/demo/consumers` test — correctly out of scope, since
`docs/openapi/order-service.yaml` defines no such endpoints for this service, confirmed by
inspection before starting.

## 4. Defense-in-depth constraint check (item 6)

Confirmed, not rebuilt. `docs/order-state-machine.md`'s own text (the "Notes on the internal
transitions" section) states the mechanism directly: *"the idempotency check ... runs before the
transition, so Scenario 4's duplicate produces no second history row."* That is: Order Service's
`order_status_history` idempotency is not a database constraint of its own (there is no
`UNIQUE(order_id, status)` or similar) — it is provided entirely by the `processed_events` ledger
claim this step adds, which now guards every write path into `OrderPersistence`'s status-transition
methods. Before this step, `appendStatus` had no guard at all and would have written a duplicate
history row and event on every redelivery; `OrderDuplicateEventIntegrationTest` is the proof that,
after this step, it does not. Nothing needed rebuilding — ADR-005 already anticipated exactly this
design ("state machine guards... a duplicate produces no second history row" — via the idempotency
check, not a separate mechanism).

## 5. Test suite verification

Ran `mvn -pl services/common,services/order-service -am test` twice consecutively with
`JAVA_HOME` pointed at Temurin 21 (required — the machine default JDK 26 breaks Mockito). Both runs:

```
com.orderfulfillment.order.dto.CreateOrderRequestValidationTest   — 6 tests, 0 failures
com.orderfulfillment.order.OrderStatusTest                        — 2 tests, 0 failures
com.orderfulfillment.order.OrderServiceIntegrationTest            — 5 tests, 0 failures (pre-existing, unmodified)
com.orderfulfillment.order.OrderDuplicateEventIntegrationTest     — 2 tests, 0 failures (new)
com.orderfulfillment.order.OrderPoisonMessageIntegrationTest      — 2 tests, 0 failures (new)
```

17/17 both times — no ordering dependence observed.

## 6. Findings for the coordination protocol

Nothing here required touching a frozen contract file (`docs/openapi/`, `docs/events/`,
`docs/order-state-machine.md`, `docs/db-ownership.md`), and nothing in `services/common/` needed a
shape it didn't already have. The one thing worth flagging explicitly, though it required no code
change and no contract change: `docs/reliability-pattern.md` §5 ("Gap 1") documents that a
dead-lettered `InventoryReserved` leaves an order stranded in `PENDING`/`INVENTORY_RESERVED`
forever, and explicitly scopes fixing that to whoever owns the state machine, not to this fan-out
step. The same is now also true, symmetrically, for a dead-lettered `PaymentAuthorized` or
`ShipmentCreated` on Order Service's other two listeners — an order can now also strand in `PAID`
or `FULFILLMENT_PENDING` if one of those is poison-message-worthy. This is the same open question
§5 already raised, just confirmed to generalize across all three of Order Service's listeners; no
new decision is proposed here.
