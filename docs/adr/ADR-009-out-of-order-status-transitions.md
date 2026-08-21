# ADR-009: Guard and defer out-of-order order-status transitions

- **Status:** Accepted. Decided and implemented after Phase 10, in Order Service only.
- **Date:** 2026-08-20

## Context

Order status is owned exclusively by Order Service (`docs/order-state-machine.md`), but inside that
service it is written by **three independently-consumed Kafka topics** — `inventory.events`,
`payments.events` and `fulfillment.events` — each with its own listener, its own partitions and its
own offsets. Kafka guarantees ordering **within** a partition of **one** topic. It guarantees nothing
between topics. Until this ADR, `OrderPersistence` wrote whatever status its caller handed it,
reading the order row only to mutate it. The transition table of `docs/order-state-machine.md` §3
existed as prose and as documentation comments; no code consulted it.

That is not merely theoretically unsound. It produced a live, reproducible correctness failure,
documented in `docs/agent-reports/phase-10-scaling-demo.md` §4.

`PaymentAuthorized` is the project's one deliberate fan-out event (`docs/events/event-catalog.md`
§3): Order Service and Fulfillment Service consume it independently, in separate consumer groups,
and **neither waits for the other**. So Fulfillment Service can process `PaymentAuthorized`, create
the shipment and publish `ShipmentCreated` before Order Service's own `OrderPaymentEventsConsumer`
has reached that same record. Under load it routinely did — cross-referenced `kubectl logs`
timestamps for `order-20044` show Fulfillment Service processing the event at `16:21:02.996` and
Order Service's own consumer processing it at `16:21:10.060`, a gap of **over seven seconds**.

Two writes then happened in the wrong order, and neither had a guard:

1. `OrderFulfillmentEventsConsumer` consumed the early `ShipmentCreated` and wrote `FULFILLED`
   directly from `PAYMENT_PENDING` — skipping `PAID` and `FULFILLMENT_PENDING`, a transition §3 does
   not contain.
2. The delayed `PaymentAuthorized` then ran and unconditionally wrote `PAID`, then
   `FULFILLMENT_PENDING`, **over the top of the already-correct terminal state**.

The order came to rest at `FULFILLMENT_PENDING` forever, with the history
`PENDING → INVENTORY_RESERVED → PAYMENT_PENDING → FULFILLED → PAID → FULFILLMENT_PENDING`. It
affected 3 of 60 orders at one Inventory Service replica and 34 of 60 at two — it gets worse with
concurrency, which is exactly the wrong direction for a system whose headline demo is scaling.

### Why the obvious fix does not work

The reflex fix is "if the precondition state isn't there yet, throw and let Kafka redeliver". It is
wrong here on two counts, both of which `docs/reliability-pattern.md` §4.3 already argues:

- **The budget is too small.** The retry policy is `ExponentialBackOff`, `maxAttempts = 3`, 0.5 s /
  1 s / 2 s — about **3.5 seconds** in total. The measured race gap was **over 7 seconds**. A valid,
  eventually-consistent event would exhaust its retries and be dead-lettered as a false failure.
- **It is the wrong mechanism regardless of the numbers.** Retrying **blocks the partition**: every
  later record for every other order waits behind it. §4.3 makes that tradeoff deliberately, for
  *genuinely transient infrastructural* failures, which are rare. Reusing it for an expected
  business-level ordering race — up to 34 of 60 orders in the measured run — would make a
  rare-failure tradeoff at common-case frequency.

Widening the backoff for everyone is worse still: it would slow the DLQ demo (Scenario 6) and make
real poison records hold a partition for a minute, to accommodate something that is not a failure at
all.

Nor is reshaping the topology on the table. Funnelling both topics through one ordered consumer
would mean removing Fulfillment Service's independent consumption of `payments.events`, and that
fan-out is a deliberate, documented design decision (`docs/events/event-catalog.md` §3). It is out of
scope here.

## Decision

**Enforce the frozen transition table on every write, and hold — rather than retry or drop — a
transition that arrives before its predecessor.**

Three parts.

### 1. The transition table becomes code

`OrderTransitions` encodes `docs/order-state-machine.md` §3's valid-predecessor set for each target
status. `OrderPersistence` classifies every requested transition against the order's **actual current
status**, read under a lock, before writing anything. There is no longer any path by which a
caller-supplied status is written without that check.

The classification has three outcomes:

| Verdict | Meaning | Action |
|---|---|---|
| `APPLY` | Current status is a valid predecessor of the target. | Write it: one `order_status_history` row, `orders.status` moves. |
| `AHEAD` | A legitimate future step whose predecessor has not been applied yet. | Park it (part 2). |
| `STALE` | The order has already passed this point, or has reached a terminal state. | Drop it, at WARN. |

`STALE` is what stops the second half of the bug: **nothing leaves a terminal state**, so a late
`PaymentAuthorized` can no longer revert a `FULFILLED` order. Distinguishing `STALE` from `AHEAD`
needs an ordering, so `OrderTransitions` also carries a monotonic ordinal along the happy-path chain
`PENDING → … → FULFILLED`. That ordinal is a derived convenience, not a contract addition — §3
remains the authority on which pairs are valid.

### 2. Out-of-order transitions are parked, not retried and not dropped

An `AHEAD` transition is written to a new table, `deferred_transitions`, in the same local
transaction as this event's `processed_events` claim. After every applied status change,
`OrderPersistence` re-offers that order's parked rows and applies each one the transition table now
permits, repeating while progress is made. So the early `ShipmentCreated` waits at
`PAYMENT_PENDING`, and the moment `PaymentAuthorized` writes `PAID` → `FULFILLMENT_PENDING`, the
parked `FULFILLED` is applied — in the very same transaction.

Three properties matter:

- **Durability equal to applying it.** The parked row and the ledger claim commit together, exactly
  as ADR-005 requires of a business change. Nothing is left to Kafka redelivery, which the ledger
  would suppress anyway (`docs/reliability-pattern.md` §2.2).
- **No partition blocking, and no time limit.** The listener returns normally. The order can wait
  seven seconds or seven hours; the mechanism does not care, because it is not a timeout.
- **The history stays honest.** Every transition still writes exactly one `order_status_history`
  row, in the order §3 defines, carrying its own `source_event_id` — the `FULFILLED` row still
  carries the `ShipmentCreated` envelope's `eventId` after the deferral. `PAID` and
  `FULFILLMENT_PENDING` are durably recorded as the real transitions they are, before `FULFILLED`,
  rather than being inferred or skipped.

A parked row that can never apply — because the order reached a terminal state it cannot follow — is
marked `ABANDONED` rather than left looking pending forever.

### 3. Transitions are serialized per order

Every transition takes `SELECT ... FOR UPDATE` on the order row before reading its status. Without
it, "read current status, classify, write" is a check-then-act race between two listener threads, or
between two Order Service replicas, and the guard could be evaluated against a status another
transaction is about to change — reintroducing the bug in a smaller window. The lock is per order,
so different orders never contend, and it is the same reasoning that makes ADR-005's ledger claim an
insert rather than a read (`docs/reliability-pattern.md` §2.2).

## Consequences

### What Order Service's aggregate status now actually guarantees

Stated precisely, because rule 18 of `docs/planning/agent-guidance.md` forbids claiming more than is
implemented:

- **`orders.status` only ever holds a status reachable by a valid sequence of
  `docs/order-state-machine.md` §3 transitions from `PENDING`.** No transition outside that table is
  ever durably written.
- **Status is monotonic and never reverts.** It never moves backwards along the happy path, and it
  never leaves a terminal state.
- **`order_status_history` is a complete, correctly-ordered record** of the transitions that were
  applied, one row each, with per-event attribution preserved across a deferral.
- **Convergence is eventual, not immediate.** An order whose events arrive out of order sits at its
  last valid status until the missing predecessor arrives; it does not jump ahead. The visible status
  is therefore always a *true* status, but not necessarily the *latest* one the rest of the system
  knows about.
- **This is still not exactly-once**, and nothing here changes that. Delivery remains at-least-once
  (`docs/events/event-catalog.md` §2); ADR-005's ledger still makes duplicate delivery of the *same*
  event a no-op, unchanged by this ADR.

### Accepted costs

- **A new table and a new failure mode to look at.** `deferred_transitions` is one more thing to
  inspect when an order looks stuck. It is also the first place that will *say* why — which the
  previous behavior never did. Like `processed_events` (ADR-005), it grows without bound if nothing
  prunes it; Sprint 2 goal 2 added `DeferredTransitionRetentionScheduler`, which purges resolved
  (`APPLIED`/`ABANDONED`) rows older than 7 days once a day and never touches a `PENDING` row
  regardless of age — deleting a live parked transition would silently erase it rather than resolve
  it.
- **A parked transition whose predecessor never arrives waited forever — until Sprint 2.** If
  `PaymentAuthorized` was dead-lettered, the order rested at `PAYMENT_PENDING` with a parked
  `FULFILLED`, indefinitely, because nothing told Order Service the predecessor had failed for good.
  Transition 9 (`→ FAILED`) closes exactly this: `OrderDeadLetterConsumer` (Sprint 2 goal 2 — see
  `docs/order-state-machine.md`'s "Implementation, Sprint 2 goal 2" note) listens on this service's
  own `orders.dlq` and calls `OrderPersistence#markFailed` for the order a dead-lettered record
  belongs to. `markFailed` re-drains that order's parked transitions after writing `FAILED`, so a
  `FULFILLED` left waiting on the now-dead-lettered `PaymentAuthorized` is marked `ABANDONED` in the
  same transaction rather than sitting `PENDING` forever. The general problem this bullet originally
  named — what Order Service should do about an order whose event never resolves — is answered for
  the "never resolves because it was dead-lettered" case; an event that is simply never produced at
  all (no publish, no DLQ record either) is not detectable from inside Order Service and remains the
  open question `docs/reliability-pattern.md` §5 flags.
- **A row lock on every transition.** Per order, held for the length of one short transaction. At
  demo volume this is not a throughput concern; at much higher volume it would become the natural
  serialization point to measure.
- **Two existing tests were wrong and were corrected.**
  `OrderServiceIntegrationTest.paymentAuthorizedThenShipmentCreatedReachesFulfilled` and
  `paymentRejectedIsTerminal` published `PaymentAuthorized` / `PaymentRejected` against an order
  still at `PENDING`, skipping `InventoryReserved`, and asserted the history
  `PENDING → PAID → FULFILLMENT_PENDING → FULFILLED`. That path is not in §3. They passed only
  because of the very defect this ADR fixes. They now drive the real sequence.

### Alternatives rejected

- **Retry until the precondition is met** — rejected above: too small a budget for the real delay,
  and it blocks the partition for a non-failure.
- **A wider, dedicated backoff for this one case** — still a timeout dressed as a guarantee, still
  partition-blocking, and it would have to be pessimistically long to cover a delay that has no
  bound.
- **Infer and back-fill the skipped states** (apply `FULFILLED` early, synthesizing `PAID` and
  `FULFILLMENT_PENDING` history rows). Produces a plausible-looking history that is partly invented,
  and then has to decide what to do with the real `PaymentAuthorized` when it arrives. The history
  would no longer be a record of what happened, which is most of its value.
- **Drop the early event and rely on redelivery** — the ledger claim makes redelivery a no-op, so
  this loses the event outright.
- **Route both topics through a single ordered consumer** — requires changing the deliberate
  `payments.events` fan-out, which is out of scope (`docs/events/event-catalog.md` §3).

## Implementation

- `services/order-service/src/main/java/com/orderfulfillment/order/OrderTransitions.java` — the table.
- `.../OrderPersistence.java` — guard, defer, drain, and the per-order lock.
- `.../DeferredTransitionEntity.java`, `.../DeferredTransitionRepository.java`,
  `.../DeferredTransitionStatus.java`, `.../StatusTransitionResult.java`.
- `.../OrderRepository.java` — `findByIdForUpdate`.
- `services/order-service/src/main/resources/db/migration/V5__deferred_transitions.sql`.
- `services/order-service/src/test/java/com/orderfulfillment/order/OrderOutOfOrderTransitionIntegrationTest.java`
  — reproduces the race **deterministically** (it publishes `PaymentAuthorized` only after observing
  that the early `ShipmentCreated` has been parked), against real Testcontainers Kafka and Postgres.
  Verified to fail with the original symptoms when the guard is disabled.
- **Sprint 2 goal 2 — transition 9 (`→ FAILED`):**
  `.../OrderDeadLetterConsumer.java` (the `orders.dlq` listener) and `OrderPersistence#markFailed`.
  `services/order-service/src/test/java/com/orderfulfillment/order/OrderFailedTransitionIntegrationTest.java`
  proves it against a real dead-lettered record.
- **Sprint 2 goal 2 — `deferred_transitions` retention:** `.../DeferredTransitionRetentionScheduler.java`,
  `DeferredTransitionRepository#deleteByStatusNotAndResolvedAtBefore`.
  `services/order-service/src/test/java/com/orderfulfillment/order/RetentionSchedulerIntegrationTest.java`
  proves both that resolved rows past the window are purged and that a `PENDING` row survives
  regardless of age.
