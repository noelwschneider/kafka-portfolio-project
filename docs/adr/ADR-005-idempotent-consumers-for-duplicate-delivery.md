# ADR-005: Use idempotent consumers for duplicate delivery

- **Status:** Accepted. Frozen in Phase 0; implemented in Phase 4.
- **Date:** 2026-08-17 (Phase 0)

## Context

Kafka delivery in this project is at-least-once (ADR-001), so every consumer will eventually see the
same record twice. This is not an edge case to be defended against pessimistically; it is the normal
consequence of ordinary events:

- a consumer processes a record, writes to the database, and crashes before committing its offset —
  on restart it reads the same record again;
- a consumer group rebalances mid-batch and a partition's uncommitted records are redelivered;
- a producer retries a send whose acknowledgement was lost.

The consequences are concrete and bad: a second reservation for the same order silently oversells
stock, a second authorization double-charges, a second shipment ships twice. And
`docs/planning/sprint-1/frontend-design.md`'s Scenario 4 promises a reviewer that a genuinely duplicated event
produces no duplicate side effect — a claim that has to be true, and visibly so.

## Decision

Every consumer is idempotent, using the `processed_events` ledger described in
`docs/planning/sprint-1/backend-design.md`'s Idempotent consumers section:

```text
processed_events
----------------
event_id        -- envelope eventId
consumer_name   -- logical consumer, e.g. "inventory.order-created"
processed_at
PRIMARY KEY (event_id, consumer_name)
```

Handling a record:

1. Check whether `(event_id, consumer_name)` is already present. If so, acknowledge and do nothing.
2. Otherwise perform the business change **and** insert the ledger row **in one local database
   transaction**.

The composite key is what lets the same event be processed once by each of several *different*
consumers — necessary because `PaymentAuthorized` is consumed independently by Order Service and
Fulfillment Service.

Supporting choices:

- **`eventId` is the identity.** A duplicate delivery of the same logical event carries the same
  `eventId` (`docs/events/event-catalog.md` §1) — which is precisely what Scenario 4 republishes.
- **The ledger is per-service**, in each service's own schema (ADR-004), because the dedup insert must
  commit in the same transaction as the business change.
- **Defence in depth via database constraints**, not the ledger alone: `payment_attempts.idempotency_key
  UNIQUE`, `shipments.order_id UNIQUE`, and `inventory_reservations UNIQUE (order_id, sku)` each make a
  double side effect impossible even if the ledger check were bypassed
  (`docs/db-ownership.md`).
- **State machine guards.** Applying a transition that has already been applied is a no-op
  (`docs/order-state-machine.md` §3), so a duplicate produces no second `order_status_history` row.

## Alternatives considered

**Kafka's idempotent producer plus transactions ("exactly-once semantics").** Kafka can deduplicate
producer retries and can make consume-transform-produce atomic within Kafka. Rejected as the primary
mechanism because it does not cover what actually matters here: these consumers' side effects land in
PostgreSQL, not in Kafka, so Kafka transactions cannot make the database write and the offset commit
atomic. Enabling it would also invite exactly the overclaim
`docs/planning/agent-guidance.md` rule 18 forbids — "we use exactly-once" — for a guarantee that would
not hold end-to-end. The idempotent producer setting is harmless and may be enabled, but the
correctness argument rests on the ledger.

**Naturally idempotent operations only** — write every handler so that reapplying it is harmless (for
example `SET status = 'PAID'` rather than an increment). Genuinely the best answer where it is
available, and used here where it is: status transitions are guarded assignments. Rejected as the
*only* mechanism because the important operations are not naturally idempotent. Reserving stock
decrements a counter, and creating a shipment inserts a row; neither is safe to repeat, and contorting
them into idempotent forms would be more fragile than an explicit ledger.

**Deduplicate on a business key instead of `eventId`** — for example, one reservation per
`(order_id, sku)` regardless of how many events arrive. Cheaper (no ledger table), and this project
keeps those constraints anyway as backstops. Rejected as the primary mechanism because it only works
where a natural unique key exists, it conflates "this event was already handled" with "this business
fact already exists", and it cannot express "this consumer has seen this event" — which is what the UI
needs to show in Scenario 4.

**An in-memory seen-set or a cache.** No schema change, fast. Rejected: it does not survive a restart,
which is exactly when duplicates arrive, and with multiple replicas each pod would have its own set.

## Consequences and tradeoffs

**Accepted costs.**

- One extra read and one extra insert per event, and a table that grows monotonically. A retention
  policy (delete rows older than N days) is needed eventually; at demo volume it is not urgent, and
  pruning is safe once records are past Kafka's own retention.
- The ledger row and the business change must share a transaction. That constrains handler structure —
  no side effect may escape the transaction boundary — and it is the reason the ledger cannot be
  shared across services.
- Idempotency protects against duplicate *side effects*, not against duplicate *work*: the second
  delivery is still fetched, deserialized, and looked up.
- Publishing is not covered. A crash between the database commit and the Kafka publish still loses an
  event; that is the dual-write problem ADR-006 addresses.
- Retryable failures interact with the ledger: a handler that fails after its transaction rolled back
  leaves no ledger row, so the retry reprocesses correctly. A handler that partially succeeded outside
  the transaction would not be safe — hence the rule above.

**What it buys.**

- Duplicate delivery becomes a demonstrable non-event rather than a lurking double-charge.
- The mechanism is visible: the ledger row, the unique constraints, and the timeline showing an event
  consumed twice with one side effect are all things a reviewer can inspect.
- It makes the honest claim available — at-least-once delivery with idempotent consumers — instead of
  the overclaim.
