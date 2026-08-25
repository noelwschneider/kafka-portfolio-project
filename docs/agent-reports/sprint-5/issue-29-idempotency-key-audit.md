# Issue #29 — do the domain services' idempotency ledgers share EventProjectionConsumer's broker-reset-fragile key?

## What changed

Nothing. This is an investigation-only task per the delegation scope; no source files were modified.

## Answer

**No.** All four domain services (Order, Inventory, Payment, Fulfillment) key their idempotency
ledgers on the envelope's `eventId` (a UUID, stable across redelivery and broker resets), combined
with a compile-time-constant `consumerName`. None of the seven domain consumer classes construct a
`ProcessedEventKey` from Kafka topic/partition/offset. This is the opposite pattern from
`EventProjectionConsumer` (issue #27), which dedupes on `(topic, partition, offset)` — physical
coordinates that a broker reset invalidates. The two components do not share the bug; #27 is isolated
to the scenario-service demo projection.

## Evidence, file:line

**1. The shared key type is explicitly designed against physical coordinates.**
`services/common/src/main/java/com/orderfulfillment/common/idempotency/ProcessedEventKey.java:14-18`:
the Javadoc states `consumerName` "must not be derived from anything that changes between
deployments (a hostname, a partition, a generated client id)". The record is `(UUID eventId, String
consumerName)` — no offset field exists in the type at all.

**2. The ledger table schema has no columns to hold physical coordinates.**
Identical DDL in all four services:
- `services/order-service/src/main/resources/db/migration/V2__processed_events.sql:10-15`
- `services/inventory-service/src/main/resources/db/migration/V4__processed_events.sql:10-15`
- `services/payment-service/src/main/resources/db/migration/V2__processed_events.sql:9-14`
- `services/fulfillment-service/src/main/resources/db/migration/V2__processed_events.sql:10-15`

```sql
CREATE TABLE processed_events (
    event_id      uuid NOT NULL,
    consumer_name text NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);
```

No `topic`, `partition`, or `offset` column exists in this table in any of the four services. Contrast
with scenario-service's own `event_records` table (`V2__events.sql`, per
`EventProjectionConsumer.java:119`), which is `UNIQUE (topic, partition, offset)` by design — a
genuinely different schema for a genuinely different (demo-observability) purpose.

**3. Every domain consumer builds the key from `envelope.eventId()`, never from Kafka metadata.**
Checked all seven listener classes; each constructs `new ProcessedEventKey(envelope.eventId(),
<compile-time-constant consumerName>)`:
- `services/order-service/src/main/java/com/orderfulfillment/order/OrderInventoryEventsConsumer.java:73-74,92-93`
- `services/order-service/src/main/java/com/orderfulfillment/order/OrderPaymentEventsConsumer.java:65-66,82-83`
- `services/order-service/src/main/java/com/orderfulfillment/order/OrderFulfillmentEventsConsumer.java:61-62`
- `services/inventory-service/src/main/java/com/orderfulfillment/inventory/InventoryOrderEventsConsumer.java:69-70`
- `services/inventory-service/src/main/java/com/orderfulfillment/inventory/InventoryPaymentEventsConsumer.java:61-62`
- `services/payment-service/src/main/java/com/orderfulfillment/payment/PaymentOrderEventsConsumer.java:64-65`
- `services/fulfillment-service/src/main/java/com/orderfulfillment/fulfillment/FulfillmentPaymentEventsConsumer.java:64-65`

None of these classes reference `ConsumerRecord.topic()`, `.partition()`, or `.offset()` at all — each
`@KafkaListener` method takes a `String message` (the decoded value), not a `ConsumerRecord`, so
physical coordinates are not even in scope at the call site.

**4. `envelope.eventId()` is generated once and never regenerated on retry.**
Traced via a subagent (its full findings folded in here since they bear directly on the answer):
- `EventEnvelope` (`services/common/src/main/java/com/orderfulfillment/common/events/EventEnvelope.java:13-21`)
  carries `eventId` as a plain field.
- `OutboxRecorder.record(...)` (e.g. `services/order-service/src/main/java/com/orderfulfillment/order/OutboxRecorder.java`)
  generates `UUID.randomUUID()` exactly once, at insert time, inside the same
  `@Transactional(propagation = MANDATORY)` transaction as the business change, and persists the
  fully-serialized envelope (including that `eventId`) into `outbox_events.payload` (jsonb).
- `OutboxDispatcher.publishPendingBatch()` (e.g.
  `services/order-service/src/main/java/com/orderfulfillment/order/OutboxDispatcher.java`) only reads
  that stored `payload` back (`objectMapper.readTree`) and re-serializes it unchanged before sending.
  On send failure the row is left `PENDING` and retried on the next tick from the same stored payload
  — same `eventId` resent, never a new one. Verified byte-identical across order-, payment-,
  fulfillment-service `OutboxDispatcher.java`; inventory-service follows the same pattern.
- The `outbox_events` table itself (`V4__outbox_events.sql` / inventory's `V6__outbox_events.sql`) has
  no dedicated `event_id` column — the id lives only inside the `payload` jsonb blob, set once and
  never rewritten by any code path.

Net effect: a redelivered domain event — whether from consumer rebalance, outbox retry, or a full
broker reset that resets offsets to 0 — carries the *same* `eventId` it always did, so the ledger's
`(event_id, consumer_name)` primary key still matches the original row and correctly no-ops the
duplicate, regardless of what topic/partition/offset it lands at this time.

**5. Design doc and code match — no drift.**
`docs/reliability-pattern.md:24-36` and `docs/planning/sprint-1/backend-design.md:368-390` both specify
the ledger as `event_id` (envelope eventId) + `consumer_name`, exactly what all four services
implement. `docs/planning/sprint-1/backend-design.md:388` even states the intent explicitly: "record
the event ID transactionally." No drift between the frozen sprint-1 design and the current
implementation.

## How this was verified

Read every file cited above directly (not summarized secondhand) except item 4's outbox-retry
mechanics, which were traced by a read-only Explore subagent scoped to files I then re-verified myself
(the `OutboxDispatcher`/migration excerpts below were re-read directly, not taken on the subagent's
word).

Static evidence confirmed against the live running stack (already up before this session — left
running afterward, untouched):

```
$ docker compose ps
NAME                                   ...  STATUS
orderfulfillment-kafka                 ...  Up 3 hours (healthy)
orderfulfillment-order-service         ...  Up 3 hours (healthy)
orderfulfillment-inventory-service     ...  Up 3 hours (healthy)
orderfulfillment-payment-service       ...  Up 3 hours (healthy)
orderfulfillment-fulfillment-service   ...  Up 3 hours (healthy)
orderfulfillment-postgres              ...  Up 3 hours (healthy)
```

Real ledger rows, keyed only on `event_id`/`consumer_name` (no offset columns exist to inspect):

```
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment \
    -c "SELECT * FROM order_service.processed_events ORDER BY processed_at DESC LIMIT 3;"
               event_id               |      consumer_name       |         processed_at
--------------------------------------+---------------------------+-------------------------------
 d4b8dac4-aab6-46e2-8991-a70712fb3fac | order.fulfillment-events  | 2026-08-25 21:36:20.686937+00
 7bb1acd6-dc6b-4eea-be7b-552dea1eb143 | order.payment-events      | 2026-08-25 21:36:20.601411+00
 da607941-41cc-43c2-9199-566c6b7ab376 | order.inventory-events    | 2026-08-25 21:36:20.500699+00
```

Behavioral proof — replayed a real, already-consumed domain event at a **brand-new Kafka offset**
(simulating exactly the offset-mismatch a broker reset produces) and confirmed the real consumer still
recognizes it as a duplicate:

```
# Pulled a previously-processed OrderCreated record off the live topic:
$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic orders.events --from-beginning \
    --max-messages 1 --timeout-ms 5000
{"eventId":"44142380-fd8c-4222-9982-ed382f90146c","payload":{...,"orderId":"order-20086",...},
 "eventType":"OrderCreated", ...}

# Re-published that exact message (same eventId) — Kafka appends it at a new, much higher offset
# than its original position:
$ echo '{"eventId":"44142380-fd8c-4222-9982-ed382f90146c", ...}' | \
    docker exec -i orderfulfillment-kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic orders.events

# inventory-service log, ~3s later:
$ docker logs orderfulfillment-inventory-service --since 15s | grep 44142380
{"message":"Skipping duplicate delivery of OrderCreated 44142380-fd8c-4222-9982-ed382f90146c
 for order-20086", ...}
```

The consumer correctly deduped despite the record arriving at a completely different physical
offset than its first delivery — the direct behavioral opposite of #27's bug, where an offset
mismatch is exactly what causes a *false negative* (treating a genuinely new event as already
processed) or, in the reset direction, would need an offset *collision* to falsely suppress a new
event. Either way, the domain ledger's `eventId`-keyed design is insensitive to the offset entirely,
which is the property #27 lacks.

No teardown needed — the stack was already running before this session started and was left running,
untouched, per "leave the environment as you found it." No containers were started, stopped, or
recreated; the one write performed was a single Kafka produce to `orders.events`, an append-only
operation with no side effect on any table beyond the dedup no-op already demonstrated.

## Judgment calls

- **Did not delete or roll back the replayed test message.** Producing one duplicate `OrderCreated`
  record to `orders.events` is consistent with the demo-repeatable nature of the events already in the
  topic (at-least-once delivery is the system's designed baseline per `docs/events/event-catalog.md`
  §2), and the consumer's own dedup logic makes it a safe no-op by design — proving that safety was
  the point of the test. Left in place rather than attempting a topic-level compaction/deletion, which
  would have been a much more invasive and riskier action for zero additional evidence value.
- **Used a subagent for one narrow sub-question (outbox retry/eventId-generation mechanics) rather
  than tracing it myself line by line.** The delegation prompt named `ProcessedEventKey.java` as the
  primary thing to read myself, which I did first and fully. The outbox-retry mechanics were a
  supporting question ("could a retry regenerate eventId") rather than the central question the task
  asked me to answer, so I scoped the subagent tightly (specific classes, specific yes/no question)
  and then re-read its cited files myself (`OutboxDispatcher.java`, the `V4/V6__outbox_events.sql`
  migrations) before including any of its claims in this report, per "trust but verify."
- **Treated `EventProjectionConsumer.java:112` as ground truth for the #27 contrast** rather than
  re-litigating #27's diagnosis — that root cause was already established in Sprint 4 and is out of
  scope to redo here; I only needed to confirm the code at that line still reads the way the task
  description says it does (it does).
- **Did not attempt to reproduce a real Kafka broker reset** (`docker compose down` without `-v` then
  back up, or a KRaft log-dir wipe) against the domain services, because the domain ledger's
  offset-independence is provable more directly and less destructively by showing a same-eventId
  redelivery at a *different* offset is caught (done above) — a broker reset is just one way to
  produce that same condition (new low offsets colliding with, or diverging from, old ones). The
  mechanism proven is the same one that would be exercised by an actual reset.

## Deliberately not covered

- **Concurrent-duplicate race behavior** (two threads racing `isProcessed`/`recordProcessed` for the
  same key) was read in the Javadoc (`ProcessedEventLedger.java:34-41`) but not exercised under actual
  concurrent load in this session — issue #29 is about the *key composition*, not the
  check-then-act race, which is a separate (and already-documented-as-handled-by-`ON CONFLICT DO
  NOTHING`) concern.
- **`OrderDeadLetterConsumer`** (`OrderConsumers.java:39-49`) was noted but not traced in detail — its
  own Javadoc states dead-letter redelivery is deliberately *not* idempotency-ledger-tracked (relies on
  the state machine's terminal-state guard instead), which is a different mechanism again and was out
  of this task's stated scope (the four *domain* consumers' ledger usage).
- **`payment_attempts.idempotency_key UNIQUE` and `shipments.order_id UNIQUE`** — mentioned in
  `docs/reliability-pattern.md:376` as a second, business-level uniqueness guard layered under the
  ledger check in `PaymentService`/`FulfillmentService`. Not verified directly in this session; it is
  a defense-in-depth layer beneath the ledger, not the ledger itself, and doesn't change the answer to
  the question asked (the ledger key is `eventId`-based either way).
- **No fix implemented, per explicit scope instruction.** If a fix is warranted here at all — it is
  not, based on this evidence — that decision and its ticketing is left to the developer, as directed.
- **`EventProjectionConsumer`'s own fix (#27) was not touched**, per explicit instruction.
