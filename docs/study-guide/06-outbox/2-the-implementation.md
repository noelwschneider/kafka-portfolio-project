# 6.2 — The implementation

[← The dual-write problem](1-the-dual-write-problem.md) · [Next: The rollout →](3-the-rollout.md)

Three small classes per service — a recorder, a dispatcher, and a scheduler — plus one table.

---

## The table

```sql
CREATE TABLE outbox_events (
    id           bigserial PRIMARY KEY,
    aggregate_id text NOT NULL,
    event_type   text NOT NULL,
    payload      jsonb NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz NULL,
    status       text NOT NULL
);
```

`V4__outbox_events.sql` in each service, identical DDL in four schemas — the same shape, and the same
reasoning, as `processed_events`.

**`payload` holds the complete envelope**, not just the domain payload. That is what lets the
dispatcher be dumb: it reads a row, sends the bytes, and marks it. It needs to know nothing about
event types or envelope construction.

**`id` is a `bigserial`.** Publication order is `ORDER BY id ASC`, which is commit order — the property
everything downstream depends on.

---

## The recorder: build the envelope now

```java
@Transactional(propagation = Propagation.MANDATORY)
UUID record(String eventType, String aggregateId, Object payload) {
    return record(eventType, aggregateId, UUID.randomUUID(), payload);
}
```

Two decisions, both explained in its Javadoc.

**The envelope is built here, at business-transaction time:**

> so `eventId`, `occurredAt` and `correlationId` describe the moment the change actually happened, and
> are identical no matter how many times the dispatcher has to resend the row.

Three consequences follow from that one choice:

- **`occurredAt` is a business time**, not a publication time — honouring the envelope contract's
  *"when the publishing service decided the event happened — not when it was written to Kafka."*
  With an outbox that gap can be seconds; with a broker outage, minutes.
- **A resend carries the same `eventId`**, so consumer-side deduplication works. If the dispatcher
  stamped IDs at send time, every resend would look like a new event and
  [Chapter 4](../04-reliability/README.md)'s ledger would be useless against exactly the duplicates
  this pattern creates.
- **The caller learns the ID before the send** — which `PaymentRequested` needs, because its
  `idempotencyKey` *is* its own event ID.

It also reuses `EventPublisher.buildEnvelope` rather than constructing an envelope itself, so there
remains exactly one place the frozen envelope is built.

**`MANDATORY`, for the same reason as the ledger:**

> an outbox insert in a transaction of its own would reintroduce exactly the dual-write window this
> class exists to close, so calling it without one fails loudly at the call site.

The second appearance of this annotation in the project, both times enforcing "this write must join
the caller's transaction, and there had better be one."

### At the call site

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
OrderEntity createPendingOrder(String orderId, String customerId, List<OrderItemEntity> items, BigDecimal totalAmount) {
    orderRepository.save(order);
    orderItemRepository.saveAll(items);
    historyRepository.save(new OrderStatusHistoryEntity(orderId, OrderStatus.PENDING, null, now));
    // …
    outboxRecorder.record(EventTypes.ORDER_CREATED, orderId, new OrderCreatedPayload(orderId, customerId, eventItems));
    return order;
}
```

Four writes, one transaction: the order, its items, its first history row, and the event. All of it, or
none of it.

And a structural point in the Javadoc worth copying:

> The event is built here rather than by the caller precisely so it cannot be forgotten: **there is no
> longer any code path that creates an order without also committing its event.**

The transaction boundary and the "an order always has an event" invariant are the same boundary.
`OrderService.createOrder` cannot get this wrong, because it no longer has the opportunity.

---

## The dispatcher: send in order, one at a time

```java
@Transactional
int publishPendingBatch() {
    List<OutboxEventEntity> pending =
            outboxRepository.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PageRequest.of(0, batchSize));
    int published = 0;
    for (OutboxEventEntity row : pending) {
        try {
            JsonNode envelopeNode = objectMapper.readTree(row.getPayload());
            kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, row.getAggregateId(), wireForm(envelopeNode))
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            row.markPublished(Instant.now());
            published++;
        } catch (Exception ex) {
            if (expired(row)) {
                row.markFailed();
                log.error("Outbox row {} … could not be published within {} — marking FAILED; "
                        + "this event was never sent and needs manual attention", …);
                continue;   // aged out; skipping it unblocks everything queued behind it
            }
            log.warn("Outbox send failed for row {} …; leaving PENDING for retry", …);
            break;          // stop the batch: sending later rows first would reorder the topic
        }
    }
    return published;
}
```

### Ordering is the whole design

> Rows are sent strictly oldest-first and one at a time, **blocking on each broker acknowledgement
> before the next send**, because ADR-001's per-partition ordering guarantee is only worth anything if
> this publisher preserves the order the transactions committed in. That is also why a send failure
> stops the batch instead of skipping ahead.

Note `.get(sendTimeoutMs, …)` — the opposite of `EventPublisher`'s fire-and-forget. Here the send is
awaited, because the next send must not start until this one is acknowledged.

**This is the pattern's real cost: publication is serial per service.** Not per partition, per
*service*. Every event queues behind every earlier event. For this system's volume that is irrelevant;
at high throughput it is the first thing you would have to address, and the usual answer is to
partition the outbox by aggregate and dispatch each partition independently.

**`break` on failure, not `continue`.** Skipping a failed row to publish a later one would deliver
events out of commit order — turning a delay into a correctness problem.

### The transaction spans the sends

> The transaction spans the sends on purpose: it holds `FOR UPDATE` on the batch, so a second instance
> of this service waits its turn rather than interleaving sends. Whatever was published before a
> failure still commits — the loop returns normally rather than throwing.

Two properties from one decision. **Multi-instance safety** — two replicas both polling would otherwise
interleave sends and reorder the topic; the row lock serializes them without any coordination service.
And **partial progress is kept**, because the loop returns rather than throwing, so five successful
sends out of ten commit their `PUBLISHED` marks.

This is also why `batch-size` is bounded — the batch size is how long one instance can hold that lock.

### Duplicates, not losses

> The send and the `PUBLISHED` mark are not atomic — a crash in between resends the row on the next
> tick. That is ADR-006's stated trade: **a lost-event problem becomes a duplicate-event problem, and
> duplicates are the one ADR-005's idempotent consumers already handle.** At-least-once, never
> exactly-once.

The dual write did not vanish. It moved — from between PostgreSQL and Kafka to between Kafka and one
`UPDATE` in PostgreSQL. And the new failure mode is one the system already solved a chapter ago.

**This is the most important sentence in the chapter.** The outbox does not achieve exactly-once. It
converts an unhandleable failure into a handled one. Anyone claiming an outbox gives exactly-once
delivery has skipped this step.

### Retry bounded by age

> The frozen schema has no retry-count column, so retries are bounded by *age* instead: a row whose
> send fails stays `PENDING` and is retried on every tick until it is older than `fail-after-ms`, at
> which point it is marked `FAILED`, logged at ERROR, and skipped so it cannot block the queue forever.

Five minutes by default. The reasoning:

> A broker outage shorter than that window therefore costs nothing but latency; a genuinely
> unpublishable row (or an outage longer than the window) surfaces as a FAILED row for a human to look
> at.

**A constraint turned into a design.** No retry-count column, so age becomes the budget — and age is
arguably the better measure anyway, since what you care about is how long an event has been undelivered,
not how many times you tried.

And:

> Nothing here ever deletes or rewrites `payload`, so a FAILED row remains a complete record of the
> event that should have been published.

`FAILED` is not a tombstone. It is evidence, and it is the only evidence there is.

### The `jsonb` round-trip

```java
/**
 * PostgreSQL's {@code jsonb} is a decomposed binary format, not the text that was inserted: it drops
 * insignificant whitespace, reorders object keys and collapses duplicates, so reading the column back
 * gives {@code {"eventId": "…"}} where the producer wrote {@code {"eventId":"…"}}. [...] records on
 * {@code orders.events} should look the same whichever service produced them, so the row is
 * re-serialized compactly on its way out. This changes formatting only.
 */
private String wireForm(JsonNode envelopeNode) {
    return objectMapper.writeValueAsString(envelopeNode);
}
```

A genuinely surprising detail, and a good thing to know: **`jsonb` does not store your text.** It
parses to a binary representation and re-serializes on read, so whitespace, key order, and duplicate
keys are all lost.

It changes nothing semantically — every consumer parses rather than string-matches — but records on one
topic should look alike whichever code path produced them, so the dispatcher normalizes on the way out.

(`json`, without the `b`, *does* preserve the original text. `jsonb` is otherwise the right choice —
it indexes and queries — so normalizing on output is the better trade.)

---

## The scheduler

```java
@Scheduled(fixedDelayString = "${orderfulfillment.outbox.poll-interval-ms:50}")
void publishPending() {
    try {
        int published = dispatcher.publishPendingBatch();
        if (published > 0) {
            log.debug("Outbox published {} event(s)", published);
        }
    } catch (Exception ex) {
        // A scheduled method that throws is simply logged by Spring and retried next tick; this
        // catch exists only to keep the message specific (e.g. the database being unreachable,
        // which is not the dispatcher's own per-row failure path).
        log.warn("Outbox poll failed; retrying on the next tick", ex);
    }
}
```

A separate bean from the dispatcher, for the third appearance of the same trap:

> Separate from `OutboxPublisher` (which owns the `@Scheduled` tick) so that `@Transactional` actually
> applies — a self-invoked call would bypass Spring's proxy, the same reason `OrderPersistence` is
> split out of `OrderService`.

`@Transactional`, `@Async`, `@Scheduled` — all proxy-based, all silently inert on self-invocation. Once
you know the pattern, a one-method class calling into another bean stops looking like over-engineering.

**`fixedDelay`, not `fixedRate`**, and the Javadoc says why:

> ticks must not stack up behind a slow batch, since two dispatchers running at once would contend on
> the same rows for no gain.

`fixedDelay` waits N ms *after the previous run finishes*; `fixedRate` fires every N ms regardless, so
a slow batch would have the next tick starting while the previous still held the row lock — threads
piling up to immediately block on each other.

**The `catch` is deliberate and narrow.** A scheduled method that throws is logged by Spring and
retried next tick anyway; this exists only to make the message specific — a database that is
unreachable entirely, as opposed to the dispatcher's own per-row failure path.

**Polling, with no notify-on-commit hook**, and that is also a stated decision:

> ADR-006 offers that as an optional latency optimization, and at the default 50 ms interval the added
> publication latency is already inside the "tens of milliseconds" the ADR budgets for, which does not
> justify a second concurrent path into the dispatcher.

Declining an available optimization because the simpler thing already meets the budget — and naming
the cost of taking it (a second concurrent path into the dispatcher) rather than just calling it
unnecessary.

### The poll interval is the latency

```yaml
orderfulfillment:
  outbox:
    # Phase 6's transactional outbox (ADR-006). The poll interval is the publication latency this
    # pattern costs: ADR-006 budgets "tens of milliseconds if the publisher polls tightly", and 50ms
    # is inside that without a notify-on-commit hook.
    poll-interval-ms: 50
    batch-size: 100
    send-timeout-ms: 10000
    fail-after-ms: 300000
```

**Every event now waits up to 50ms before publication.** That is the pattern's other cost, stated
plainly and tuned deliberately — fast enough to be invisible in a demo timeline, slow enough not to
hammer the database with empty queries.

Each of the other three is also a named trade, and the comments say what each bounds:

- **`batch-size: 100`** — *"sends are sequential and acknowledged one at a time (ordering), so this
  bounds how long one transaction can hold its FOR UPDATE lock."*
- **`send-timeout-ms: 10000`** — how long to block on one acknowledgement before treating the send as
  failed.
- **`fail-after-ms: 300000`** — the age-based retry budget.

Four numbers, four stated reasons. The same discipline as
[Chapter 4](../04-reliability/README.md)'s derived bounds.

---

[← The dual-write problem](1-the-dual-write-problem.md) · [Next: The rollout →](3-the-rollout.md)
