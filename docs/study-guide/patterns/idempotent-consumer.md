# Pattern — The idempotent consumer

**Where it's introduced:** [Chapter 4, section 1](../04-reliability/1-idempotent-consumers.md).
**Where it recurs:** every `@KafkaListener` in all four business services.

---

## The problem

Kafka delivers **at least once**. A consumer will see the same record twice, and this is not an edge
case — it is the ordinary consequence of ordinary events:

- A consumer processes a record, writes to its database, and crashes before committing its offset. On
  restart it reads the same record again.
- A consumer group rebalances mid-batch (a deployment, a scale-up, a missed heartbeat) and a
  partition's uncommitted records are redelivered to their new owner.
- A producer's send times out, the producer retries, and both records land.

Kafka cannot fix this for you. The commit that would have to be atomic is *your database write plus
Kafka's offset commit*, and those are two different systems.

The consequences are not symmetrical. A duplicated read is harmless. A duplicated **side effect** is
a second reservation against the same stock, a second charge, a second shipment. In this project the
worst case is inventory *release*: applying it twice hands the same units back to stock twice,
inventing inventory out of nothing.

So the requirement is: **processing a record twice must have the same effect as processing it once.**

## Three ways to get there

**Make the operation naturally idempotent.** `SET status = 'PAID'` is idempotent; `balance = balance -
10` is not. Where you can express the work as an assignment or an upsert keyed by something stable,
you need nothing else. This covers less than you would hope.

**Use a natural business key with a uniqueness constraint.** "One shipment per order" enforced by
`UNIQUE (order_id)` means a second attempt fails at the database. Excellent as a backstop, but the
failure arrives as an exception you must then classify, and it only works where such a key exists.

**Keep a ledger of what you have processed.** Record `(eventId, consumer)` as you apply the change,
and skip anything already recorded. General, explicit, and works regardless of what the operation
does.

This project uses the third as the primary mechanism, with the second as defence in depth.

---

## The design

### The table

```sql
CREATE TABLE processed_events (
    event_id      uuid NOT NULL,
    consumer_name text NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);
```

**Per service, never shared.** Four schemas, four identical tables. The reason is correctness rather
than taste: the ledger row must commit **in the same local transaction** as the business change it
guards, and a transaction cannot span two services' schemas. It also avoids putting four services'
consumers into one write hotspot and coupling four migration histories.

**The key is composite** — `(eventId, consumerName)`, not `eventId` alone. One event is legitimately
processed once *by each* of several consumers: Order Service and Fulfillment Service both consume
`PaymentAuthorized` for different reasons. Keying on `eventId` alone would let whichever arrived first
suppress the other.

**`consumerName` must be stable across restarts.** Conventionally `"<service>.<listener>"` —
`"inventory.order-created"`, `"order.payment-events"`. Never a hostname, a partition number, a
generated client ID, or anything else that varies between deployments:

> a redelivery after a restart would fail to match the ledger row it is supposed to match.

**One name per listener method, not per event type.** A listener handling both `InventoryReserved`
and `InventoryReservationFailed` uses one `consumerName` for both — the composite key already
disambiguates by `eventId`.

### The insert is the authority, not the read

This is the part that is easy to get subtly wrong.

```java
public boolean isProcessed(ProcessedEventKey key) { /* SELECT count(*) … */ }

@Transactional(propagation = Propagation.MANDATORY)
public boolean recordProcessed(ProcessedEventKey key) {
    int inserted = jdbcClient.sql(
            "INSERT INTO " + tableName + " (event_id, consumer_name, processed_at) "
          + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
        .param(key.eventId()).param(key.consumerName()).param(now).update();
    return inserted != 0;
}
```

A naive implementation reads "have I seen this?" and then applies the change if not. **That is
check-then-act, and it is not atomic.** Two listener threads — and a service runs several per topic —
can both read *absent* and both apply.

So the read is demoted to a cheap early-out, and the decision is made by an
`INSERT … ON CONFLICT DO NOTHING` **inside the business transaction**:

- Insert affected one row → you claimed it → apply the change.
- Insert affected zero rows → someone else already claimed it → skip.

A concurrent duplicate blocks on the uncommitted row and then sees zero rows affected — exactly the
answer it should get. The database's own concurrency control does the work, which is the general shape
of every correct solution to check-then-act.

Note the asymmetry in what the two answers are worth: `isProcessed` returning **true** is final
(rows are never purged while the event could still be redelivered), while **false** only means "not
yet, as of now."

### The claim must be inside the business transaction

```java
@Transactional(propagation = Propagation.MANDATORY)
public boolean recordProcessed(ProcessedEventKey key) { … }
```

`MANDATORY` means: join the caller's transaction, and **throw if there isn't one**. It is mechanical
enforcement of the rule the whole pattern rests on. Calling this outside a transaction would allow
two failure modes:

- The ledger row commits, the business change does not → the event is **silently lost**, because
  the redelivery will be skipped as a duplicate.
- The business change commits, the ledger row does not → the redelivery **applies it twice**.

`MANDATORY` turns both into a loud failure at the call site instead of a production mystery.

### The claim must be the first statement, at the right level

It belongs in the method that *owns the business transaction* — not in the listener above it, and not
in a retry loop wrapping it:

> Claiming the event one level up would leave the row in an outer transaction that commits separately
> from the reservation it is supposed to be atomic with.

And it should be that method's first statement, so a duplicate short-circuits before any work runs.

This has a pleasing consequence where a retry loop is involved: an attempt that rolls back rolls back
its ledger row too, so a reservation that takes seven optimistic-lock attempts still leaves **exactly
one** ledger row — written by the attempt that actually committed.

---

## The shape in a listener

```java
public void onMessage(String message) {
    EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
    CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
}

private void handle(EventEnvelope<JsonNode> envelope) {
    // 1. Filter FIRST — before the ledger is touched
    if (!EventTypes.ORDER_CREATED.equals(envelope.eventType())) {
        return;
    }
    ProcessedEventKey eventKey = new ProcessedEventKey(envelope.eventId(), CONSUMER_NAME);

    // 2. Cheap early-out
    if (processedEventLedger.isProcessed(eventKey)) {
        log.info("Skipping duplicate delivery of {} for {}", envelope.eventId(), envelope.aggregateId());
        return;
    }

    // 3. Delegate — the real claim happens inside the domain's own transaction
    inventoryService.reserve(orderId, lines, eventKey);
}
```

**Filtering before touching the ledger** matters more than it looks:

> A skipped record has no side effect to deduplicate, and recording it would fill the ledger with rows
> for events this service never acts on.

## Retention

The ledger grows monotonically. A row is only ever needed to answer "was this already processed?" for
as long as Kafka could still redeliver the event — so **purging rows older than the topic's retention
is safe**, by the same reasoning that makes the ledger work at all.

The project's default window is 7 days, matching Kafka's own default `log.retention.hours=168`, with
a daily scheduled `DELETE … WHERE processed_at < ?`. Deliberately unsophisticated: housekeeping, not
a latency-sensitive path.

## What this does *not* buy you

Worth being precise, because it is a common overclaim:

- **It is not exactly-once.** The record is still delivered more than once; the *side effect* happens
  once. Same observable outcome, completely different mechanism.
- **It does not protect against a genuinely new event that duplicates work.** Two distinct
  `OrderCreated` events for the same order have different `eventId`s and both get processed. The
  business-key constraint is what catches that.
- **It does not help across services.** Each service deduplicates independently, which is exactly
  what the composite key exists to allow.
