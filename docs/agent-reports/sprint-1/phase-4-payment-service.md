# Phase 4 Report — Payment Service (fan-out step)

**Date:** 2026-08-18
**Scope:** applying the reliability pattern designed once in `docs/reliability-pattern.md` /
`docs/agent-reports/phase-4-pattern-design.md` (reference implementation: Inventory Service) to
Payment Service. Mechanical application, per `docs/planning/execution-plan.md` §2 — no design
decisions re-litigated. Ran concurrently with sibling agents applying the same pattern to Order
Service and Fulfillment Service; only `services/payment-service/` was touched.

**Result: 11 tests, 0 failures** (7 pre-existing + 4 new), green on two consecutive full-suite
runs of `services/payment-service`, confirming no ordering dependence.

---

## 1. What changed

| File | Change |
|---|---|
| `db/migration/V2__processed_events.sql` | **New** — the ledger, copied verbatim (column-for-column, same composite PK) from Inventory Service's `V4__processed_events.sql`, in `payment_service` schema. |
| `application.yml` | **New** — `orderfulfillment.reliability.processed-events-table: payment_service.processed_events`. |
| `PaymentConsumers.java` | **New** — the one listener's id (`payment-requested`) and `processed_events.consumer_name` (`payment.payment-requested`). |
| `PaymentKafkaReliabilityConfig.java` | **New** — one `DefaultErrorHandler` bean → `payments.dlq`, built by `ConsumerErrorHandlerFactory`. |
| `PaymentOrderEventsConsumer.java` | `id = "payment-requested"` on the `@KafkaListener`; filter → `isProcessed()` early-out → keyed `authorize()` call → no-op on `DUPLICATE`. |
| `PaymentService.java` | New `ProcessedEventLedger` dependency; new 4-arg `authorize(orderId, amount, idempotencyKey, eventKey)` overload that calls `recordProcessed()` as its first statement inside the existing `@Transactional(REQUIRES_NEW)` method and returns `PaymentOutcome.duplicate()` if the claim is lost. Old 3-arg `authorize(...)` kept as a convenience overload (delegates with `eventKey = null`) for callers with nothing to deduplicate against. |
| `PaymentOutcome.java` | New `Kind.DUPLICATE` and `PaymentOutcome.duplicate()` factory, distinct from `PROVIDER_ERROR`/`REJECTED` so a duplicate publishes nothing. |
| `PaymentServiceApplication.java` | `excludeFilters = TypeExcludeFilter` fix (docs/agent-reports/phase-4-pattern-design.md §4.1), applied before any `@TestConfiguration` fixture was added. |
| `PaymentServiceTest.java` | Constructor call updated for the new `ProcessedEventLedger` parameter (mocked; the unit tests exercise the no-event-key overload and never touch the ledger). |
| `AbstractIntegrationTest.java` (test) | Added `jdbcClient` (ledger reads) and `kafkaTemplate` (raw/poison publishing) fields — the same additions Inventory Service's base class has, needed by the two new test classes below. |
| `PaymentDuplicateEventIntegrationTest.java` | **New** — Scenario 4. |
| `PaymentPoisonMessageIntegrationTest.java` | **New** — Scenario 6. |

No file under `services/common/`, `services/inventory-service/`, `services/order-service/`, or
`services/fulfillment-service/` was touched. No `/demo/consumers` controller was built — confirmed
`docs/openapi/payment-service.yaml` defines no such path, so `ConsumerControl`/`ConsumerState` do
not apply here, per the brief. No outage test was written, for the same reason.

---

## 2. How the tests verify Phase 4's exit criteria

Both test classes run against real Testcontainers Kafka (`apache/kafka:4.0.0`) and Postgres
(`postgres:16-alpine`), with no mocking of the mechanism under test and no hardcoded sleeps — every
wait is a real predicate via Awaitility or a bounded poll-until-found loop, following the Inventory
Service test classes verbatim in structure.

**`PaymentDuplicateEventIntegrationTest`** (2 tests):
- `republishingTheSamePaymentRequestedEventAuthorizesOnlyOnce` — one envelope, same `eventId`, sent
  twice to `orders.events`. Asserts exactly one `payment_attempts` row for the order and exactly one
  `processed_events` row for `(eventId, "payment.payment-requested")`. The negative (no second row
  after the duplicate) is proven with `await().during(5s)`, not a single sample.
- `theDuplicateDoesNotRepublishTheOutcomeEvent` — counts `PaymentAuthorized` records on
  `payments.events` for the order over a 15-second drain; asserts exactly one.

**`PaymentPoisonMessageIntegrationTest`** (2 tests), both poison forms `docs/scenarios.md` names,
published as real bytes to `orders.events`:
- an envelope with `eventVersion: 99` → `UnsupportedEventVersionException`;
- a valid envelope whose `payload` is a bare string → `JacksonException` in `payloadAs()`.

Each asserts the record lands on `payments.dlq` with the original key and bytes, and full metadata:
`x-delivery-attempts: 1` (the honest count — these are non-retryable, so asserting `1` is itself
the proof the classification took effect), `x-failure-retryable: false`, root-cause class/message,
original topic/partition/offset/consumer-group, and the dead-lettering timestamp.

No outage test exists for Payment Service, per the brief (`/demo/consumers` is not in this
service's frozen OpenAPI contract, so there is nothing to pause/resume through a real endpoint).

---

## 3. How the Kafka-eventId ledger and the `idempotency_key` DB constraint relate

They deduplicate on two different identities and both need to stay:

- **`processed_events (event_id, consumer_name)`** — the Kafka delivery identity. `event_id` is the
  envelope's `eventId`, which a *redelivery* of the same logical message reuses
  (`docs/events/event-catalog.md` §1). This is what Scenario 4 exercises: the same message, sent
  twice by Kafka (broker retry, consumer rebalance replay, or a test forcing a republish).
- **`payment_attempts.idempotency_key UNIQUE`** — the *business* identity carried in
  `PaymentRequestedPayload.idempotencyKey()`, set by whoever originates the payment request
  (Order Service). It guards against two *different* Kafka events — different `eventId`s — that both
  claim to represent the same underlying payment intent, e.g. Order Service publishing
  `PaymentRequested` twice for one order due to its own retry logic, each with a fresh `eventId`.

The ledger claim happens first (`recordProcessed()`, first statement in `authorize()`'s
transaction), and the UNIQUE constraint is defence-in-depth one layer further in, per ADR-005's own
example (`payment_attempts.idempotency_key UNIQUE`, referenced by name in
`docs/reliability-pattern.md` §8, point 7). In the normal Scenario 4 case the ledger check already
returns `duplicate` before `repository.save()` is ever reached, so the constraint never fires. It
would only fire on the business-duplicate case above (same `idempotencyKey`, different `eventId`),
where it throws a `DataIntegrityViolationException` — a `NonTransientDataAccessException` subtype —
which the shared classifier already treats as non-retryable, so that case is dead-lettered rather
than silently accepted or endlessly retried. No new code was needed to make that interaction safe;
it falls out of the existing classification.

---

## 4. Where the shared pattern didn't quite fit, and what was done about it

Nothing in `services/common/` needed to be routed around. Two small local decisions, both
consistent with the brief and with how Inventory Service structured its own fan-out:

- **`PaymentOutcome` needed a fourth `Kind`.** Inventory Service's `ReservationResult`/`ReleaseResult`
  already had a `duplicate` outcome pattern to copy; `PaymentOutcome` only had three (`AUTHORIZED`,
  `REJECTED`, `PROVIDER_ERROR`). Added `DUPLICATE` with a `duplicate()` factory, matching that
  precedent exactly — a duplicate is not a business failure and must not re-publish anything, so it
  needed to be distinguishable from `PROVIDER_ERROR` (which the consumer turns into a thrown
  exception) and `REJECTED` (which publishes `PaymentRejected`).
- **`authorize()` kept a convenience 3-arg overload** rather than forcing every caller to pass a
  `ProcessedEventKey`. This mirrors Inventory Service's own `reserve`/`release` overloads (event-key
  variant plus a plain variant) and kept `PaymentServiceTest`'s five existing unit tests — which
  exercise `PaymentService` directly with a mocked repository and no Kafka/ledger infrastructure —
  working with a one-line constructor update instead of a rewrite.
- **No demo controller, no `ConsumerControl` wiring, no outage test** — confirmed directly against
  `docs/openapi/payment-service.yaml` before starting, which defines no `/demo/consumers` path for
  this service (unlike Inventory and Fulfillment). Building one anyway would have been scope creep
  against a frozen contract; not building it is not a gap, it's the contract as written.
- **`AbstractIntegrationTest` was missing `jdbcClient` and `kafkaTemplate` fields** that Inventory
  Service's copy already had (needed respectively to read the ledger directly and to publish raw/
  malformed bytes `EventPublisher` cannot produce). Added both, matching Inventory Service's fields
  exactly rather than inventing a different shape.

Everything else — the ledger (`ProcessedEventLedger`/`ProcessedEventKey`), the error handler
(`ConsumerErrorHandlerFactory`), backoff, exception classification, and DLQ header names — is used
unmodified from `services/common/`.

---

## 5. Test suite confirmation

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
mvn -q -DskipTests -pl services/common,services/payment-service -am install
mvn -pl services/payment-service test
```

Run twice consecutively:

```
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

both times — confirming order-independence rather than a pass by ordering luck, per the brief's
requirement. The 7 pre-existing tests (`PaymentServiceTest` — 5 unit tests; `PaymentServiceIntegrationTest`
— 2 integration tests) pass unmodified in behavior; `PaymentServiceTest`'s constructor call needed a
mechanical update for the new `ProcessedEventLedger` constructor parameter only.
