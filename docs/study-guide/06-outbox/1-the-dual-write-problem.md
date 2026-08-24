# 6.1 — The dual-write problem

[← Chapter 6](README.md) · [Next: The implementation →](2-the-implementation.md)

The second of [Chapter 3](../03-kafka-and-services/README.md)'s open gaps, deliberately left open for
five chapters so that it could be understood before being fixed.

---

## The gap, restated

`EventPublisher.publish` sends to Kafka **after** the business transaction has committed:

```java
kafkaTemplate.send(topic, aggregateId, json);
```

Not even blocked on. So:

```
BEGIN; INSERT INTO orders …; COMMIT;   ← durable
                                        ← process dies here
kafkaTemplate.send(OrderCreated);       ← never happens
```

ADR-006 states the consequence exactly:

> The order exists, is visible over `GET /api/orders/{orderId}`, and will never progress — no consumer
> was ever told about it. **Nothing retries, because from the database's point of view the work
> succeeded.**

That last clause is what makes it nasty. There is no error, no failed request, no dead-letter record,
no alert. The order sits at `PENDING` forever, and the only way to find it is to go looking.

The event catalog carried the same warning from Phase 0:

> Until Phase 6 (transactional outbox), publishers persist their business change and then publish —
> so a crash between commit and publish loses the event.

**A known limitation, written down at the time.** That is what makes it a documented trade rather than
a bug — and it is also why this chapter can exist as a fix rather than an incident.

> **Pattern — [The transactional outbox](../patterns/transactional-outbox.md)**
> The dual write in general, why 2PC and Kafka transactions cannot help, CDC as the answer for a real
> system, the table shape, and the five implementation details that matter.
>
> **Read it before continuing.** This chapter covers what is specific to this codebase.

---

## Why the obvious inversion is worse

Publish first, then commit? ADR-006:

> Removes the lost-event case by introducing a phantom-event case: consumers act on a state change the
> publisher then rolls back. **Rejected as strictly worse — a lost event leaves an order stuck, while
> a phantom event corrupts other services' state.**

Worth having ready as an answer. Both orderings have a window; they differ in **whose problem the
window becomes**. Losing an event is a local failure — one order, stuck, findable. A phantom event is a
distributed failure: Inventory Service has reserved stock for an order that does not exist, and no
amount of looking at Order Service reveals it.

**When both options are wrong, prefer the one whose failure stays local.**

---

## The mistake ADR-006 made, and corrected

This is the best example in the project of a decision being revisited on its merits, and it is worth
following closely.

Phase 6 scoped the outbox to **Order Service only**, on this reasoning:

> The other publishers lose an event that a redelivery can regenerate, because their publishes are
> themselves reactions to consumed events — if `InventoryReserved` is lost, the `OrderCreated` that
> caused it can be reprocessed.

Plausible. Inventory Service publishes *in reaction to* consuming `OrderCreated`; if its publish is
lost, surely Kafka redelivers `OrderCreated` and Inventory publishes again?

**No — and the reason is a mechanism added in a different chapter.**

> That is **not true of this implementation**, and the mistake matters. Every event-driven publish site
> in all four services claims its `processed_events` row *inside* the business transaction (ADR-005
> requires exactly that), so a redelivery is short-circuited by the ledger before it can republish
> anything: the event is not regenerated, it is **silently skipped**. A crash between such a commit
> and its publish strands the order just as permanently as a lost `OrderCreated` does — only at a
> later status.

Trace it:

1. Inventory Service consumes `OrderCreated`, reserves stock, **claims the ledger row in the same
   transaction**, commits.
2. Crash before `InventoryReserved` is published.
3. Kafka redelivers `OrderCreated` — the offset was never committed.
4. The consumer checks the ledger. **Already processed.** Skip.
5. The order is stuck at `PENDING` forever.

The idempotency mechanism from [Chapter 4](../04-reliability/README.md) — which is entirely correct,
and required — **removes the self-healing property that Phase 6's scoping decision assumed**. Two
individually correct designs interacting to produce a failure neither has on its own.

That is the thing worth taking away. **Neither ADR is wrong; the interaction was unexamined.** And it
was not discoverable by reading either document alone — you have to hold both mechanisms in your head
at once and ask what happens in the gap between them.

The same reasoning also expanded the fix *within* Order Service. Phase 6 routed **both** of its publish
sites through the outbox, not just the first:

> a crash after this commit but before a post-commit publish would leave a redelivered
> InventoryReserved to be discarded as a duplicate [...] stranding the order at PAYMENT_PENDING
> exactly as a lost OrderCreated strands it at PENDING.

And what redelivery *does* still cover:

> the narrower case of a consumer that crashes **before** committing anything at all.

Redelivery protects work that never started. It cannot protect work that finished but failed to
announce itself — because the ledger, correctly, cannot tell those apart.

---

## The alternatives, and the one you should name

ADR-006's rejected options are covered in the
[pattern page](../patterns/transactional-outbox.md); two are worth pulling out here because they are
what an interviewer will ask about.

**"Why not just do nothing? It's a demo."**

> Simplest, and honestly adequate for a demo whose processes rarely die at the wrong microsecond.
> Rejected because the dual-write problem is one of the project's headline talking points, and
> **demonstrating the fix is worth considerably more than describing the problem.**

An honest answer that says the quiet part: the pattern is here partly *because it is worth
demonstrating*. That is a legitimate reason in a portfolio project, and stating it is better than
inventing a scale requirement that does not exist.

**"Why not Debezium / CDC?"**

Rejected on two stated grounds — the operational cost of Kafka Connect plus a connector, and the fact
that CDC events are shaped by table structure, so producing the designed envelope would need a
transformation step. Plus a third, stated plainly:

> It would also hide the mechanism the project is trying to demonstrate — the outbox's value here is
> partly pedagogical. **Worth naming as the answer for a real system with many publishers.**

Naming the better answer for a different context is a stronger position than defending your choice as
universally correct. "For a real system with many publishers I'd reach for CDC; here the outbox is
explicit, has no extra infrastructure, and shows the mechanism" is a complete answer.

---

[← Chapter 6](README.md) · [Next: The implementation →](2-the-implementation.md)
