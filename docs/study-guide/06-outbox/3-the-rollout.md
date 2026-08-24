# 6.3 — The rollout

[← The implementation](2-the-implementation.md) · [Chapter 6 ↑](README.md)

Phase 6 shipped the outbox in one service. Sprint 2 shipped it in the other three. The gap between
those two events is the interesting part.

---

## What Phase 6 shipped, and why only one service

`implementation-phases.md`'s Phase 6 says *"at least the most important publisher, likely Order
Service"* — so the scoping was in the plan from Phase 0, and ADR-006 picked the same target:

> it is the only publisher whose lost event strands an order that a user has already been told was
> accepted.

That reasoning is about **blast radius**, and it is sound as far as it goes. A lost `OrderCreated`
strands an order the user was told was accepted. It is the most visible failure.

What made it *incomplete* was the second half of the argument — that the other three would self-heal
through redelivery. As [section 1](1-the-dual-write-problem.md) showed, they would not, because their
`processed_events` claim commits inside the same transaction and short-circuits the redelivery.

**Phase 6 shipped the right service first, for a partly wrong reason.** Which is a common and
recoverable situation, and the recovery is the thing worth studying.

---

## How the correction was recorded

ADR-006 does not have its scoping paragraph edited away. It keeps the original text and appends a
correction block:

> **Correction, Phase 6 (implementation).** This section originally continued: "The other publishers
> lose an event that a redelivery can regenerate…" That is **not true of this implementation**, and
> the mistake matters.

And a second one, later:

> **Correction, Sprint 2 goal 2.** The gap this ADR originally left open for Inventory, Payment and
> Fulfillment Service is closed.

Three things about this practice.

**The wrong reasoning is preserved.** Anyone who read the ADR before the correction, or who has the
old argument in their head, can find out specifically what was wrong with it. Deleting it would leave
them with a document that no longer matches their memory and no explanation.

**The correction says *why*, not just *what*.** *"Every event-driven publish site claims its
`processed_events` row inside the business transaction, so a redelivery is short-circuited by the
ledger."* That is the reusable insight — someone else's outbox scoping decision can be checked against
it.

**Both corrections are dated and attributed to a phase.** The ADR reads as a record of a decision *and
its subsequent history*, which is what an ADR is actually for.

This is one place where the guide's own convention differs from the repo's. This project's docs are
generally supposed to *"state current content only, no embedded revision history"* — and an ADR is
precisely the exception, because the decision's history **is** its content.

---

## What Sprint 2 actually did

Three services, each getting the same four things:

| | |
|---|---|
| `V4__outbox_events.sql` (or `V6` for Inventory) | Identical DDL, own schema |
| `OutboxRecorder` | `MANDATORY`, builds the envelope at transaction time |
| `OutboxDispatcher` | Ordered batch, `FOR UPDATE`, age-bounded retry |
| `OutboxPublisher` | `fixedDelay` scheduler |

And one edit each, at the publish site:

| Service | Transaction | Events now recorded |
|---|---|---|
| **Inventory** | `InventoryReservationExecutor.attemptReserve` / `release` | `InventoryReserved`, `InventoryReservationFailed`, `InventoryReleased` |
| **Payment** | `PaymentService.authorize` | `PaymentAuthorized`, `PaymentRejected` |
| **Fulfillment** | `FulfillmentService.createShipment` | `ShipmentCreated` |

In every case the publish moved **into an existing `REQUIRES_NEW` transaction that already held both
the business change and the `processed_events` claim.** So the transaction that was already the unit
of "this event has been handled" became the unit of "and its consequence will be announced."

The consumers got simpler:

> this consumer no longer publishes anything itself. `InventoryService` (by way of
> `InventoryReservationExecutor`) records the outbound event to `outbox_events` inside the same
> transaction as the reservation; `OutboxPublisher` sends it to Kafka afterward.

**Publishing moved out of the listener and into the domain transaction** — which is also where it
always belonged, since the decision to publish is a consequence of the business change rather than of
having consumed a record.

### One duplication worth noticing

Four services now have four near-identical copies of `OutboxRecorder`, `OutboxDispatcher`,
`OutboxPublisher`, `OutboxEventEntity`, `OutboxEventRepository`, and `OutboxStatus`. Thirty files
where six shared ones might do.

Compare `ProcessedEventLedger`, which is **one** class in `common` used by all four
([Chapter 4](../04-reliability/1-idempotent-consumers.md)) — and whose Javadoc argued explicitly
against per-service copies as *"four copies of the one thing Phase 4's fan-out is most likely to let
drift."*

The same argument applies here and was not made. Two things differ, which partly explains it:

- The ledger is **two SQL statements against `JdbcClient`** with no entity mapping. The outbox uses a
  JPA entity and a Spring Data repository, which are harder to share generically across schemas.
- The dispatcher hard-codes its **destination topic** — `KafkaTopics.ORDERS_EVENTS` in Order
  Service's copy — so a shared version would need that parameterized, the way
  `ConsumerErrorHandlerFactory` parameterizes its DLQ topic.

> **Open question.** Neither difference looks decisive, and the error-handler factory demonstrates the
> pattern for parameterizing exactly this kind of per-service value. This is the largest piece of
> duplication in the codebase and the repo does not record a decision about it — worth asking whether
> it was considered and rejected, or simply followed the shape of Order Service's existing code during
> the Sprint 2 rollout.

---

## Stale documentation this created

Rolling a pattern out to three more services touched a lot of prose, and not all of it was updated.
Two known instances, both found while writing this guide:

**`EventPublisher`'s Javadoc** still says:

> Phase 6 closed that gap in Order Service only [...] Inventory, Payment and Fulfillment Service still
> publish this way, deliberately and documented.

Not true since Sprint 2. See [Chapter 3](../03-kafka-and-services/1-events-on-the-wire.md).

**ADR-004's decision section** still says:

> `outbox_events` exists only in Order Service (ADR-006).

Not true either. ADR-006 carries both corrections; ADR-004 carries none.

Neither is dangerous — nothing depends on them — but together they make a point worth carrying into
[Chapter 10](../10-retrospective/README.md): **the code was updated consistently and the prose was
not.** A rollout that touches four services touches every document that described the previous state,
and those references live in Javadoc, ADRs, and design docs that no test exercises.

---

## Where the pattern now stands

All four services publish through an outbox. No business change in this system can commit without its
event also being committed.

What that does and does not buy, precisely:

**Buys:** no event is lost after its business change commits. The failure mode that stranded an order
silently, with no error anywhere, is gone.

**Costs:** up to 50ms of publication latency; serial publication per service; more duplicates, not
fewer.

**Does not buy:** exactly-once. Never has, never will —
*"at-least-once, never exactly-once (agent-guidance.md rule 18)."*

That last line appears in the dispatcher's own Javadoc, in the class most likely to be described as
providing reliable delivery. Exactly where it should be.

---

[← The implementation](2-the-implementation.md) · [Chapter 6 ↑](README.md) · [Chapter 7 — Containers and Kubernetes →](../07-containers-and-kubernetes/README.md)
