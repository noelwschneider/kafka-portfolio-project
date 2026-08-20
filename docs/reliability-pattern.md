# Reliability Pattern — Idempotency, Retry, and DLQ

**Status:** the operational reference for Phase 4. Written once, sequentially, so that all four
services implement one pattern rather than four similar ones
(`docs/planning/execution-plan.md` §4, Phase 4 row).

**Where this sits.** `docs/adr/ADR-005-idempotent-consumers-for-duplicate-delivery.md` decides
*what and why*; `docs/planning/backend-design.md`'s Reliability Patterns section sketches the
outline. This file is the *how*: the concrete Spring Kafka wiring, the exception classification for
**this** codebase, the numbers and the reasoning behind them, and a checklist. It does not
re-decide anything ADR-005 decided.

**Reference implementation:** Inventory Service (`services/inventory-service/`). Everything below
is real code you can read, not a proposal. Shared pieces live in `services/common/`
(`com.orderfulfillment.common.idempotency` and `com.orderfulfillment.common.kafka`).

**A claim this document does not make.** Delivery is at-least-once
(`docs/events/event-catalog.md` §2). Idempotent consumers make duplicate *delivery* produce no
duplicate *side effect*. That is not exactly-once, and nothing here should ever be described as
exactly-once (`docs/planning/agent-guidance.md` rule 18). §7 lists what is still not covered.

---

## 1. The `processed_events` ledger

Frozen in `docs/db-ownership.md` §2 — restated here only so you do not have to open two files, and
**not** to be redesigned:

```text
processed_events
----------------
event_id        uuid        -- envelope eventId
consumer_name   text        -- logical consumer, e.g. "inventory.order-created"
processed_at    timestamptz
PRIMARY KEY (event_id, consumer_name)
```

Three properties of that shape do work later on, so they are worth naming:

- **The key is composite** because one event is legitimately consumed by several *different*
  consumers — `PaymentAuthorized` by both Order Service and Fulfillment Service. Deduplicating on
  `event_id` alone would let whichever consumer arrived first suppress the others.
- **The table is per-service**, in that service's own schema, with identical DDL. It cannot be
  shared, because the dedup insert has to commit in the same *local* transaction as the business
  change (§2).
- **`event_id` is the envelope's `eventId`**, which a redelivery of the same logical event reuses
  (`docs/events/event-catalog.md` §1). This is exactly what Scenario 4 republishes.

Each service creates its own copy in its own Flyway migration. Inventory Service's is
`V4__processed_events.sql`.

---

## 2. How a `@KafkaListener` uses it

ADR-005's rule in one sentence: **the ledger row and the business change commit together, and no
side effect escapes that transaction.** Everything in this section follows from it.

### 2.1 The shape

```java
@KafkaListener(id = "order-created", topics = KafkaTopics.ORDERS_EVENTS, groupId = "inventory-service")
public void onMessage(String message) {
    EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
    CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
}

private void handle(EventEnvelope<JsonNode> envelope) {
    // 1. Not interested? Return before touching the ledger.
    if (!EventTypes.ORDER_CREATED.equals(envelope.eventType())) {
        return;
    }

    // 2. Cheap early-out. Not the guard that matters — see 2.2.
    ProcessedEventKey eventKey = new ProcessedEventKey(envelope.eventId(), "inventory.order-created");
    if (processedEventLedger.isProcessed(eventKey)) {
        log.info("Skipping duplicate delivery of OrderCreated {} for {}", ...);
        return;
    }

    // 3. Business change + ledger claim, in one transaction, one layer down.
    ReservationResult result = inventoryService.reserve(orderId, lines, eventKey);
    if (result.duplicate()) {
        return;                       // a concurrent delivery won the claim; it publishes
    }

    // 4. Publish only after that transaction committed.
    eventPublisher.publish(...);
}
```

And in the transactional method itself
(`InventoryReservationExecutor.attemptReserve`, `@Transactional(REQUIRES_NEW)`):

```java
if (eventKey != null && !processedEventLedger.recordProcessed(eventKey)) {
    return ReservationResult.DUPLICATE;
}
// ... the business change, in this same transaction
```

### 2.2 Why the claim is an insert, not the earlier read

ADR-005 says "check, then apply". A bare check-then-act is not atomic, and this service runs three
listener threads per topic (`spring.kafka.listener.concurrency: 3`), so two deliveries of the same
event can both read "absent" and both apply. So:

- `isProcessed()` is a **cheap early-out only**. It saves the second delivery from redoing work,
  and it is what produces the visible "consumed twice, applied once" log line. It is never load
  bearing.
- `recordProcessed()` — `INSERT ... ON CONFLICT DO NOTHING`, inside the business transaction — is
  the **authority**. A concurrent duplicate blocks on the uncommitted row, then sees zero rows
  affected, which is exactly the answer it should get. Its `@Transactional(propagation = MANDATORY)`
  makes calling it outside a transaction fail loudly rather than silently break the guarantee.

### 2.3 Where the claim has to live

The claim must go in the innermost method that *is* the business transaction — not in the listener,
and not in a wrapper around it.

Inventory Service makes the reason concrete. `InventoryService.reserve` retries optimistic-lock
conflicts up to 25 times, and each attempt is its own `REQUIRES_NEW` transaction. If the claim were
made in an outer transaction around the loop, the ledger row would commit separately from the
reservation it is supposed to be atomic with. Claiming inside also makes the loop behave correctly
for free: an attempt that loses the CAS rolls its ledger row back with everything else, so a
reservation that takes seven attempts still leaves exactly one ledger row — written by the attempt
that actually committed.

### 2.4 Rules that fall out of this

1. **Filter before you claim.** An event this consumer ignores has no side effect to deduplicate,
   and recording it would fill the ledger with rows for events the service never acts on.
   (`orders.events` also carries `PaymentRequested`; `payments.events` also carries
   `PaymentAuthorized`.)
2. **Publish after commit, never inside.** A Kafka publish is not transactional with the database.
   It goes after the transactional call returns. The window this leaves is ADR-006's, and it is
   listed in §7 — do not paper over it here.
3. **`consumer_name` is a compile-time constant** of the form `<service>.<event>`. Never derive it
   from a hostname, a partition, or a generated client id: a ledger row written under one name and
   looked up under another does not deduplicate. Inventory Service keeps both its names in
   `InventoryConsumers`.
4. **Do not delete ledger rows** while the event could still be redelivered. Pruning is safe only
   once records are past Kafka's retention; at demo volume no retention policy is needed yet
   (ADR-005 "Accepted costs").

---

## 3. Retryable vs. non-retryable, for this codebase

`docs/planning/backend-design.md` §8.2's examples are generic. These are the actual classes, as
configured in `ConsumerErrorHandlerFactory`.

### 3.1 Non-retryable — dead-lettered on the first delivery

| Exception | Why retrying cannot help |
|---|---|
| `UnsupportedEventVersionException` | Required by `docs/events/event-catalog.md` §5: *"Retrying cannot fix a schema it doesn't understand."* |
| `tools.jackson.core.JacksonException` | The bytes are not the envelope/payload we expect. They will not parse differently in 500 ms. |
| `org.springframework.dao.NonTransientDataAccessException` | Spring's own name for "this will fail the same way if you try again": constraint violations, invalid usage, impossible domain data. |
| `IllegalArgumentException` | A malformed value that reached the domain layer. |

Spring Kafka's own built-in non-retryable defaults (`DeserializationException`,
`MessageConversionException`, `ClassCastException`, …) stay in force alongside these.

### 3.2 Retryable — everything else, including the default

The genuinely retryable class in this system is *infrastructural*, not content-driven:

- **`ObjectOptimisticLockingFailureException`** — the important one. It is a
  `TransientDataAccessException`, so it is **not** covered by the `NonTransientDataAccessException`
  entry above, deliberately. See §5.
- `TransientDataAccessException` generally, `CannotAcquireLockException`, connection blips.
- **Anything unrecognised.** An unclassified failure defaults to retryable, which is the safer
  default: a wrongly-retried permanent failure costs 3.5 seconds and still ends in the DLQ with its
  metadata intact, whereas a wrongly-non-retried transient failure discards real work.

### 3.3 The consequence worth stating plainly

In this codebase, **almost every failure a record's own content can cause is non-retryable**. A
record that fails deterministically is by definition not worth retrying. That is why Scenario 6's
poison records are dead-lettered after a single delivery and their DLQ metadata says
`x-delivery-attempts: 1` — that is the honest number, and reporting the configured maximum instead
would be a lie about what happened.

Bounded retries with backoff are therefore demonstrated on the retryable arm, whose only realistic
trigger is contention or a transient fault.

---

## 4. Retry and DLQ wiring

### 4.1 Per service, this is the whole of it

```java
@Configuration
public class InventoryKafkaReliabilityConfig {
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerErrorHandlerFactory factory) {
        return factory.create(KafkaTopics.INVENTORY_DLQ);
    }
}
```

Spring Boot's Kafka auto-configuration applies a single `CommonErrorHandler` bean to the listener
container factory, so this one bean covers every listener in the service without any of them naming
it. The only per-service input is the DLQ topic.

### 4.2 What the factory builds

- **`DeadLetterPublishingRecoverer`** with a fixed destination resolver
  `(record, ex) -> new TopicPartition(dlqTopic, -1)`. A service dead-letters to **its own**
  `<domain>.dlq` regardless of which topic the record arrived on — Inventory Service consumes
  `orders.events` and `payments.events` and both go to `inventory.dlq`, because the failure belongs
  to the consumer, not to the publisher (`docs/events/event-catalog.md` §2). Partition `-1` leaves
  the partition unset so the producer partitions by key (= `orderId`), keeping per-order ordering
  inside the DLQ; resolving to the source partition number would assume the DLQ has at least as many
  partitions as every source topic.
- **`ExponentialBackOff`**, `maxAttempts = 3`, initial 500 ms, multiplier 2.0, max interval 2 s,
  jitter 0. Four deliveries in all, spaced 0.5 s / 1 s / 2 s, ~3.5 s total.
- **The non-retryable list of §3.1** via `addNotRetryableExceptions`, which matches subclasses and
  unwraps `ListenerExecutionFailedException`.
- **`DeliveryAttemptTracker`** as a `RetryListener`, so the DLQ record can carry the true attempt
  count.

### 4.3 Why those numbers

Three constraints, not roundness:

- Retrying **blocks the partition** — every later record for those orders waits behind it. A budget
  an order of magnitude larger would turn one poison record into a visible outage of a whole
  partition, which is a worse failure than dead-lettering promptly.
- The retryable class here is **genuinely transient**; such failures clear in
  milliseconds-to-seconds, so extra attempts buy nothing.
- Scenario 6 asks a reviewer to *watch* retries happen and then see the record land in the DLQ.
  3.5 s is long enough to see in a UI and short enough to sit through.

Exponential rather than fixed, because the point of backoff is to sample a *different* moment, not
to wait a fixed amount. Jitter is off because the retry timing is part of what the scenario shows.

### 4.4 DLQ record contents

The record keeps the **original key and the original bytes**, so a corrected replay is possible.
Spring's standard headers supply original topic / partition / offset / timestamp / consumer group
and the exception class, message and stack trace. Four more are added in `DlqHeaders`, because
Scenario 6 requires "the error inspectable and the retry count shown" and Spring supplies neither
usefully:

| Header | Meaning |
|---|---|
| `x-delivery-attempts` | Real number of deliveries before dead-lettering — `1` for non-retryable, `4` for exhausted retries. Never the configured maximum. |
| `x-failure-retryable` | Which arm of the classifier the failure took. |
| `x-failure-class` | Class of the **root cause**. Spring's `kafka_dlt-exception-fqcn` reports the `ListenerExecutionFailedException` wrapper, which is the same for every failure and so says nothing. |
| `x-failure-message` | Message of the root cause, for the same reason. |
| `x-dead-lettered-at` | When it was dead-lettered, as opposed to when it was produced. |

The same information also goes to the service's own log at ERROR, with the full stack, so an
operator does not have to read the dead-letter topic to find out what happened.

### 4.5 Topics

All four `.dlq` topics are declared in `KafkaTopicConfig` (in `services/common/`) with 3 partitions,
like the domain topics — not left to broker auto-creation, so the failure path is as deterministic
as the happy path.

---

## 5. Gap 1 — retry exhaustion now has a defined destination

`docs/agent-reports/phase-3-inventory-concurrency.md` §7.1 left this open:
`InventoryReservationFailed.reason` is frozen to `INSUFFICIENT_STOCK | UNKNOWN_SKU`, and neither is
true when a reservation loses 25 optimistic-lock races in a row. Before Phase 4, that exception
escaped the listener, the default error handler logged it and seeked past, and the order was
stranded in `PENDING` forever.

**Resolved by routing, with no contract change.** `ObjectOptimisticLockingFailureException` is a
`TransientDataAccessException` and so classifies retryable (§3.2). The record is redelivered up to
three more times with 0.5 s / 1 s / 2 s backoff — each redelivery being a fresh 25-attempt loop
against fresh state, at a moment far enough away that the contention has almost certainly cleared —
and if it still fails it lands on `inventory.dlq` with its metadata. Every step is safe because the
losing attempt's transaction, ledger row included, rolled back, so redelivery re-reads fresh state
and cannot write twice.

`docs/events/event-catalog.md` §2 already reserves the `.dlq` topics for exactly this — *"Records
that exhausted retries, plus failure metadata"* — so **no enum value was added and no frozen file
changed**. Proven end to end by `KafkaRetryAndDlqIntegrationTest`.

**What this does *not* fix, stated plainly.** An order whose reservation is dead-lettered still
never receives `InventoryReserved` or `InventoryReservationFailed`, so it stays in `PENDING`. Phase 4
changed that from *silently lost* to *preserved and inspectable*, which is the reliability half of
the problem. The other half — what Order Service should do about an order whose reservation never
resolves — is a state-machine question, not a reliability-pattern one, and is **out of scope here**.
It is flagged for whoever owns `docs/order-state-machine.md`; the options are a timeout transition,
a replay of the dead-lettered record, or an operator action, and none of them belong to this step.

---

## 6. The `/demo/consumers` pause/resume mechanism

Frozen in each service's OpenAPI document since Phase 0, implemented for real in Phase 4. Backed by
`ConsumerControl` in `services/common/`, over `KafkaListenerEndpointRegistry`. Per
`docs/planning/agent-guidance.md` rule 9 it lives under `/demo`, never `/api`.

- **Logical listener name = the `@KafkaListener`'s `id`.** That is the registry key, the
  `consumerName` path variable, and the `name` field in the response. Inventory Service uses
  `order-created` and `payment-rejected`, matching the frozen OpenAPI examples. Note this is a
  *different* namespace from `processed_events.consumer_name`, which is service-qualified
  (`inventory.order-created`); keep both, keep them in step, do not conflate them.
- **`pause()` / `resume()`, not `stop()` / `start()`.** `stop()` leaves the consumer group,
  triggering a rebalance on the way out and another on the way back — so in a multi-instance
  deployment the paused instance's partitions would be reassigned to a running one and the
  "backlog" would be quietly processed anyway, which is the opposite of what Scenario 5
  demonstrates. `pause()` keeps the consumer in the group with its partitions assigned and simply
  stops delivering records. It also matches the frozen contract's vocabulary — the field is
  `paused`.
- **The call returns only once the pause is effective.** A pause takes effect on the container's
  next poll; returning early would let Scenario 5 publish into the gap and see its first order
  processed by a consumer it believes it has already paused. The wait is bounded (10 s) and the
  *observed* state is reported either way — never an optimistic `true`.
- **`paused` is read back from the container** (`isContainerPaused()`), not from anything the
  service remembers. There is no flag to get out of step with reality.
- Both operations are idempotent, and an unknown name is a `404` via `NotFoundException`.

---

## 7. What this pattern does **not** cover

Say these out loud rather than letting them be assumed away:

- **Publishing is not covered.** A crash between the database commit and the Kafka publish still
  loses an event. That is the dual-write problem of
  `docs/adr/ADR-006-transactional-outbox-for-db-kafka-consistency.md`, addressed in Phase 6 for
  Order Service only.
- **Duplicate *work* is not prevented**, only duplicate *side effects*: the second delivery is still
  fetched, deserialized and looked up.
- **A handler with a side effect outside its transaction is not protected.** That is why §2's rule
  is a rule.
- **The ledger grows monotonically.** A retention policy is needed eventually; it is not urgent at
  demo volume.
- **Ordering between topics is not covered, and retry is not the tool for it.** Idempotency makes a
  duplicate harmless; it says nothing about two *different* events for the same aggregate arriving in
  the wrong order off two different topics. That is a real failure mode in this system — see
  `docs/adr/ADR-009-out-of-order-status-transitions.md`, which handles it in Order Service with a
  state-machine guard and a hold, deliberately *not* with §4.3's retry budget: 3.5 s is far shorter
  than the observed race, and blocking a partition is the wrong price to pay for something that is
  not a failure.
- **This is not exactly-once**, and no document, UI string or README may say it is.

---

## 8. Checklist for a fan-out service

Everything a service needs, in order. Order, Payment and Fulfillment each do this once; Inventory
Service is the worked example to copy from.

1. **Migration.** Add `processed_events` to your schema, matching §1 exactly. Copy
   `services/inventory-service/src/main/resources/db/migration/V4__processed_events.sql`.
2. **Configuration.** Set `orderfulfillment.reliability.processed-events-table` to
   `<your_schema>.processed_events` in your `application.yml`. (The unqualified default exists only
   so a service that has not yet done this still starts.)
3. **Consumer names.** Add a small constants class like `InventoryConsumers` holding, for each
   listener: its `@KafkaListener` id (matching your OpenAPI document's `/demo/consumers` example)
   and its `processed_events.consumer_name` (`<service>.<event>`).
4. **Listener ids.** Put `id = "..."` on every `@KafkaListener`. Without it the demo endpoints have
   no stable name to address.
5. **Idempotency.** In each listener: filter for the event types you handle, then `isProcessed()`
   early-out, then pass the `ProcessedEventKey` down into the method that owns the business
   transaction, and have that method call `recordProcessed()` as its first statement and abort if it
   returns `false`. Give your result type a `duplicate` outcome distinct from "failed", so a
   duplicate publishes nothing while a genuine failure still publishes its outcome event.
6. **Error handler.** One `@Configuration` with one `DefaultErrorHandler` bean from
   `ConsumerErrorHandlerFactory.create(KafkaTopics.<YOUR>_DLQ)`. Do not re-tune the backoff or the
   classifier locally — if you believe your service needs different numbers, change them here for
   everyone and say why, per `docs/planning/execution-plan.md` §5.
7. **Backstops.** Confirm your service's own defence-in-depth constraint from ADR-005 is really in
   the schema: `payment_attempts.idempotency_key UNIQUE`, `shipments.order_id UNIQUE`,
   `inventory_reservations UNIQUE (order_id, sku)`.
8. **Demo endpoints.** A thin `@RestController` under `/demo/consumers` delegating to
   `ConsumerControl` — copy `DemoConsumerController`. No logic of your own.
9. **Tests.** Against real Testcontainers Kafka + Postgres, no mocks of the mechanism, no sleeps:
   - **duplicate** — republish an identical record (same `eventId`, key and bytes); assert one side
     effect, one ledger row, and no second outcome event;
   - **outage** — pause through the real HTTP endpoint, publish, assert nothing happens *for a
     continuous interval* (a single sample can pass by looking too early), resume, assert the
     backlog drains;
   - **poison** — publish a genuinely unprocessable record; assert it reaches your `.dlq` with the
     original bytes and full metadata, and that `x-delivery-attempts` is the honest number.
10. **Do not** re-implement the ledger, the error handler, the backoff or the pause mechanism
    locally. If `services/common/` does not fit your service, that is a finding to report, not a
    thing to work around (`docs/planning/execution-plan.md` §5 rule 4).
