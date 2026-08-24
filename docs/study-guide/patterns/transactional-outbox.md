# Pattern — The transactional outbox

**Where it's introduced:** [Chapter 6](../06-outbox/README.md).
**Where it recurs:** all four business services, identically.

---

## The problem: the dual write

Every publisher in an event-driven system does two writes that must either both happen or neither:

1. change its own database,
2. publish the event that tells everyone else about it.

Two different systems. No shared transaction. So there is a window between them, and a crash in that
window leaves the two out of step.

**Publish after commit** — the obvious ordering — loses events:

```
BEGIN; INSERT order; COMMIT;    ← durable
                                 ← crash here
kafka.send(OrderCreated);        ← never happens
```

The order exists. It is visible over the API. **Nothing will ever process it**, and nothing retries,
because from the database's point of view the work succeeded.

**Publish before commit** trades that for something worse — a *phantom* event describing a state
change that never persisted, which downstream services act on. A lost event leaves one aggregate
stuck; a phantom event corrupts other services' state.

Neither ordering works, because the problem is not the ordering. It is that two systems cannot commit
together.

## Why the textbook answers do not apply

**Two-phase commit / XA.** The classic answer. Kafka does not support it. And where XA is available it
is operationally painful — a blocking coordinator, in-doubt transactions after a coordinator failure.

**Kafka transactions.** Atomic *within Kafka*. They cannot enrol a PostgreSQL commit, which is the
entire difficulty.

**Change Data Capture** (Debezium tailing the write-ahead log). Genuinely strong — no application
publisher and no dual write at all, because the log *is* the commit. The costs are operational (Kafka
Connect plus a connector to run) and structural: CDC events are shaped by your table structure, so
producing a designed event envelope needs a transformation step. **The right answer for a real system
with many publishers**, and worth naming as such.

**Polling business tables** — find rows whose event was never published. Needs a per-aggregate notion
of "already published," which means either a column per aggregate type or an inference from state.
Fragile and does not generalize.

## The pattern

Make the event durable **in the same transaction** as the business change, by writing it to a table in
the same database. Publish from that table afterwards.

```
BEGIN;
  INSERT INTO orders …;
  INSERT INTO outbox_events (payload) VALUES (<full envelope>);
COMMIT;                          ← both, or neither

-- later, a separate poller:
SELECT … FROM outbox_events WHERE status = 'PENDING' ORDER BY id FOR UPDATE;
kafka.send(…);
UPDATE outbox_events SET status = 'PUBLISHED';
```

The dual write does not disappear — it **moves**, from between two systems to between one system and
itself. And that second gap has a completely different failure mode: a crash between the send and the
`PUBLISHED` mark resends the row on the next tick.

> **A lost-event problem becomes a duplicate-event problem** — and duplicates are the one thing
> idempotent consumers already handle.

That sentence is the whole pattern. It does not achieve exactly-once; it converts an unhandleable
failure into one you have already solved.

## The table

```sql
CREATE TABLE outbox_events (
    id           bigserial PRIMARY KEY,
    aggregate_id text NOT NULL,
    event_type   text NOT NULL,
    payload      jsonb NOT NULL,     -- the complete envelope
    created_at   timestamptz NOT NULL,
    published_at timestamptz NULL,
    status       text NOT NULL       -- PENDING | PUBLISHED | FAILED
);
```

**One per service, in that service's own schema.** Same reason as the idempotency ledger: the insert
must commit with the business change, which requires it to be in the same database.

**`id` is a monotonic sequence**, and ordering by it is what preserves the order transactions
committed in.

**`payload` holds the complete envelope**, not just the domain data — see below.

## Five details that matter

### Build the envelope at business-transaction time

Not later, in the poller. The event ID, timestamp, and correlation ID must describe **the moment the
change actually happened**, and must be identical however many times the row is resent.

A poller that stamped `occurredAt` at send time would report infrastructure delays as business times,
and a resend would produce a *different* event ID — defeating the consumer-side deduplication the
whole design depends on.

It also means the ID is known to the caller before the send, which matters when a payload references
its own event ID.

### Enforce the transaction

```java
@Transactional(propagation = Propagation.MANDATORY)
UUID record(String eventType, String aggregateId, Object payload) { … }
```

`MANDATORY` throws if there is no surrounding transaction. An outbox insert in a transaction of its
own **reintroduces exactly the dual-write window the pattern exists to close** — and would do so
silently. Fail at the call site instead.

### Send in order, one at a time, blocking on each acknowledgement

Per-partition ordering is only worth anything if the publisher preserves the order transactions
committed in. That means strictly oldest-first, one send at a time, waiting for each broker
acknowledgement before the next.

It also means **a send failure must stop the batch** rather than skip ahead — publishing later rows
first would reorder the topic.

This is the pattern's real cost: publication is serial per service.

### Bound the retries, and have a terminal state

A row that can never be published must not block the queue forever. Bound it — by retry count, or by
age if the schema has no counter — and move the row to a terminal `FAILED` state, logged loudly,
skipped so everything behind it proceeds.

Never delete or rewrite the payload. A `FAILED` row is the complete record of an event that should
have been sent, and it is the only evidence a human has.

### The poll interval *is* the added latency

Every event now waits up to one tick before publication. That is the pattern's other cost, it is
directly tunable, and the floor is set by how tightly you are willing to poll. (A notify-on-commit
hook can eliminate it; a poll interval is the simpler default.)

## What it does and does not give you

**Does:** no event is ever lost after its business change commits. The two are one commit.

**Does not:** exactly-once delivery. A duplicate is *more* likely now, not less, because a crash
between send and mark resends the row. That is the deliberate trade — and it is only safe if consumers
are already idempotent.

**Does not:** ordering across services, or delivery within a bounded time. It guarantees an event will
eventually be published, in the order its service committed it.
