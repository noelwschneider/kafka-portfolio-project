# 4.4 — Out-of-order transitions

[← Inventory contention](3-inventory-contention.md) · [Next: Pausing consumers →](5-pausing-consumers.md)

The third of [Chapter 3](../03-kafka-and-services/README.md)'s open gaps, and the one that took
longest to find. This is ADR-009, and it is the best story in the project.

---

## The problem, stated exactly

From [Chapter 1](../01-design-contract/2-the-event-contract.md), repeated because everything here
follows from it:

> Kafka guarantees ordering **within a partition** of **one** topic. It guarantees nothing between
> topics.

Now look at where Order Service's status comes from. ADR-009 opens with it:

> Order status is owned exclusively by Order Service, but inside that service it is written by
> **three independently-consumed Kafka topics** — `inventory.events`, `payments.events` and
> `fulfillment.events` — each with its own listener, its own partitions and its own offsets.

Three listeners, three topics, three offset positions, no ordering relationship between any of them.

And the topic that makes it bite is `payments.events`, because of a fan-out that is otherwise a
feature:

- **Order Service** consumes `PaymentAuthorized` → `PAID` → `FULFILLMENT_PENDING`.
- **Fulfillment Service** consumes the same `PaymentAuthorized` → creates a shipment → publishes
  `ShipmentCreated` to `fulfillment.events`.
- **Order Service** consumes `ShipmentCreated` → `FULFILLED`.

Two of Order Service's three inputs are racing. `ShipmentCreated` travels a *different topic* from
`PaymentAuthorized`, and nothing sequences them.

## What actually happened

> `OrderPersistence` wrote whatever status its caller handed it, reading the order row only to mutate
> it. The transition table of `docs/order-state-machine.md` §3 existed as prose and as documentation
> comments; **no code consulted it**.

So under load, in Phase 10:

1. `PaymentAuthorized` and `ShipmentCreated` both arrive at Order Service, on different topics.
2. `ShipmentCreated` is processed **first**, writing `FULFILLED` straight out of `PAYMENT_PENDING` —
   skipping `PAID` and `FULFILLMENT_PENDING` entirely.
3. The late `PaymentAuthorized` is then processed, and **overwrites the terminal `FULFILLED` back to
   `FULFILLMENT_PENDING`.**

A completed order silently reverting to in-progress. It required real concurrency to reproduce and
never appeared in a functional test, because in a functional test the events arrive in the order you
sent them.

> **We got this wrong.** Found in Phase 10's scaling work — by load, not by review. The full story is
> in [Chapter 10](../10-retrospective/README.md). Everything below is the fix, and the build-along
> builds it from the start.

---

## Three things the fix needs

Notice the bug has three separable failure modes, and a single guard would not address all of them:

1. A transition that **moves the order backwards** must be dropped.
2. A transition that **leaves a terminal state** must never be applied.
3. A transition that is **legitimately in the future** — its predecessor simply has not arrived yet —
   must *not* be dropped. `ShipmentCreated` really did happen. Discarding it would strand the order at
   `FULFILLMENT_PENDING` forever.

That third case is what makes this more than a validity check. **Some invalid-right-now transitions
are valid later**, and telling those apart is the whole design.

---

## The classifier

```java
enum Verdict { APPLY, STALE, AHEAD }

static Verdict classify(OrderStatus current, OrderStatus target) {
    if (VALID_PREDECESSORS.getOrDefault(target, Set.of()).contains(current)) {
        return Verdict.APPLY;
    }
    // Already there: a redelivery that got past the ledger, or a deferred row drained twice.
    if (current == target) {
        return Verdict.STALE;
    }
    // Nothing leaves a terminal state. This is the half of the guard that stops a late
    // PaymentAuthorized from reverting FULFILLED.
    if (current.isTerminal()) {
        return Verdict.STALE;
    }
    Integer currentProgress = PROGRESS.get(current);
    Integer targetProgress = PROGRESS.get(target);
    if (currentProgress != null && targetProgress != null && targetProgress < currentProgress) {
        return Verdict.STALE;
    }
    return Verdict.AHEAD;
}
```

Two data structures behind it, and the distinction between them matters:

**`VALID_PREDECESSORS`** — the frozen transition table from
[Chapter 1](../01-design-contract/3-state-and-api-contracts.md), transcribed with its row numbers as
comments. This *is* the contract.

**`PROGRESS`** — a monotonic ordinal along the happy path (`PENDING`=1 … `FULFILLED`=6), and its
Javadoc is careful to say what it is not:

> used only to tell an *earlier* transition arriving late (which must never undo later progress) from
> a *later* transition arriving early (which is legitimate and merely premature). It is **not part of
> the frozen contract**; it is a derived ordering over §3's happy-path chain.

Marking a derived helper as *not the contract* is a small act of documentation hygiene that pays off
the next time someone edits the state machine. Absent for the three failure outcomes, which branch off
the chain rather than sitting on it.

The order of the checks is deliberate: valid → already there → terminal → backwards → otherwise
premature. `AHEAD` is the fallthrough, which is the safe direction — an unclassifiable transition gets
parked and re-examined rather than discarded.

---

## Deferral

```java
case AHEAD -> {
    defer(order, status, sourceEventId);
    yield StatusTransitionResult.asDeferred();
}
```

A `deferred_transitions` row (migration `V5`) parks the target status and its `sourceEventId`. The
event has been consumed, its offset will be committed, its ledger row is written — the *work* is
durable even though the *transition* has not been applied.

That last point is why deferral needs a table rather than a queue or an in-memory buffer: the record
is gone from Kafka's perspective, so if the parked transition is lost, nothing will ever re-deliver
it.

## Draining

```java
private void drainDeferred(OrderEntity order) {
    for (int pass = 0; pass < MAX_DRAIN_PASSES; pass++) {
        boolean appliedAny = false;
        List<DeferredTransitionEntity> parked = deferredTransitionRepository
                .findByOrderIdAndStatusOrderByIdAsc(order.getId(), DeferredTransitionStatus.PENDING);
        if (parked.isEmpty()) return;

        for (DeferredTransitionEntity deferred : parked) {
            switch (OrderTransitions.classify(order.getStatus(), deferred.getTargetStatus())) {
                case APPLY -> {
                    writeStatus(order, deferred.getTargetStatus(), deferred.getSourceEventId());
                    deferred.resolve(DeferredTransitionStatus.APPLIED, Instant.now());
                    appliedAny = true;
                }
                case STALE -> {
                    log.warn("Abandoning deferred {} for order {}: order is at {}, which it can never follow", …);
                    deferred.resolve(DeferredTransitionStatus.ABANDONED, Instant.now());
                }
                case AHEAD -> { /* still waiting on its predecessor — leave it parked */ }
            }
        }
        if (!appliedAny) return;
    }
    log.error("Deferred-transition drain for order {} did not settle in {} passes; …", …);
}
```

**Every successful transition drains.** Applying a status may unblock something parked, so the drain
runs after every `APPLY`.

**The loop repeats** because applying one parked transition can unblock another. Concretely: with
`PAID` and `FULFILLED` both parked, applying `PAID` enables `FULFILLMENT_PENDING`, which enables
`FULFILLED` — a chain that resolves in one drain across several passes.

**It uses the same classifier**, so parked transitions are subject to exactly the same rules as
arriving ones. One definition of validity, two entry points.

**Three terminal outcomes for a parked row** — `APPLIED`, `ABANDONED`, or still `PENDING`. The
`ABANDONED` case matters: a transition can become permanently impossible, and marking it explicitly
means the table does not accumulate rows nobody can explain.

**The pass bound:**

> Safety stop on the drain loop. [...] this bounds it well above the longest legal chain (six
> statuses) so a hypothetical cycle cannot spin a transaction forever.

10 passes against a maximum legal chain of 6, and an `ERROR` log if it ever hits the bound. **A bound
derived from the domain, plus loud failure if the derivation was wrong** — the same shape as
[section 3](3-inventory-contention.md)'s 25 attempts.

---

## The lock the whole thing rests on

`classify` reads the current status and then decides. That is check-then-act, and it has exactly the
same hazard as the inventory race — except here the answer is pessimistic rather than optimistic:

```java
/**
 * {@code SELECT ... FOR UPDATE} on one order row. Every status transition takes this lock first
 * (ADR-009), which serializes the three independently-consumed topics that write
 * {@code orders.status} against each other for a given order — without it, "read current status,
 * decide, write" is a check-then-act race between two consumer threads (or two Order Service
 * replicas) and the guard could be evaluated against a status another transaction is about to
 * change. Per-order only: different orders never contend.
 */
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select o from OrderEntity o where o.id = :id")
Optional<OrderEntity> findByIdForUpdate(@Param("id") String id);
```

**Pessimistic here, optimistic for inventory** — and the reason is the access pattern, not a
preference:

- Inventory conflicts are **rare** (four SKUs, most orders not competing) and the operation is a
  simple increment. Detect-and-retry costs nothing on the uncontended path.
- Order-status conflicts are **expected** — three topics deliberately write the same row, for every
  single order. And the operation is not an increment but a multi-step decision that reads state,
  classifies, writes, and drains a second table. Retrying all of that on conflict would be far more
  expensive than waiting.

*"Per-order only: different orders never contend"* is what keeps the lock cheap. It serializes the
three listeners for **one** order, not the topic.

Two orders never wait on each other, so this does not become a throughput ceiling — which
[Chapter 8](../08-observability-and-scaling/README.md)'s high-volume scenario would have found
immediately if it did.

---

## Retention, again

`deferred_transitions` accumulates resolved rows, so Sprint 2 added
`DeferredTransitionRetentionScheduler` alongside the `processed_events` one — same 7-day window,
purging only `APPLIED` and `ABANDONED` rows.

**Only resolved rows.** A `PENDING` row is still waiting for a predecessor that may yet arrive;
purging it would silently drop a real transition.

---

## What this buys, precisely

> an order whose events arrive out of order now converges to the correct terminal state *slightly
> later* rather than jumping ahead, and an invalid transition is now dropped at WARN instead of being
> written.

**Converges** is the right word and worth using. The system is eventually consistent by design; this
makes the convergence *monotonic*. An order's status never goes backwards, never skips a step, and
never leaves a terminal state — but it may lag reality briefly while a predecessor is in flight.

That is a much better property than "always correct instantly," because the latter is not achievable
across three independently-consumed topics without either global ordering (which would cost the
fan-out) or synchronous coordination (which would cost the architecture).

One consequence the changelog flags for anyone writing tests against this:

> Anything that relied on Order Service accepting a status write from an arbitrary current state
> (including tests that skip `InventoryReserved`) will need to drive the real sequence.

Two existing tests asserted an invalid state-machine path and had to be corrected. **Tests can encode
a bug**, and a fix that breaks tests is not automatically a regression.

---

## Why not retry instead?

The obvious alternative — let the premature transition fail and be redelivered — was considered and
rejected:

> Retry/backoff was rejected as the mechanism: the existing budget (~3.5 s) is shorter than the
> observed race, and it blocks the partition for what is not a failure.

Two independent reasons, and the second is the stronger one. **A premature transition is not a
failure.** Nothing went wrong; two things simply arrived in an unhelpful order. Routing it through
error handling would block a partition, consume a retry budget, and eventually dead-letter a perfectly
valid event — treating a normal consequence of the architecture as a fault.

---

[← Inventory contention](3-inventory-contention.md) · [Next: Pausing consumers →](5-pausing-consumers.md)
