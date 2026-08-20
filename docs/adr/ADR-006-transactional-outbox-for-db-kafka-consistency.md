# ADR-006: Add a transactional outbox for database/Kafka consistency

- **Status:** Accepted. Frozen in Phase 0; implemented in Phase 6, in Order Service only. Phases
  1–5 deliberately shipped the simpler publish-after-commit behavior, with the resulting failure
  window documented rather than hidden; Inventory, Payment and Fulfillment Service still ship it.
- **Date:** 2026-08-17 (Phase 0)

## Context

Every publisher in this project does two writes that must either both happen or neither:

1. change its own database (insert the order, mark the reservation),
2. publish the event that tells the rest of the system about it.

They are two different systems, so there is no shared transaction. `docs/planning/sprint-1/backend-design.md`'s
Transactional event publishing section poses the question directly: what happens if the service commits
its database transaction but fails before publishing?

The answer, with publish-after-commit, is a silently stuck order. Suppose Order Service commits an order
as `PENDING` and then the process dies before `OrderCreated` reaches Kafka. The order exists, is visible
over `GET /api/orders/{orderId}`, and will never progress — no consumer was ever told about it. Nothing
retries, because from the database's point of view the work succeeded. The inverse ordering
(publish first, then commit) trades this for a worse failure: an event describing a state change that
never persisted, which downstream services will act on.

This is the dual-write problem, and `docs/planning/portfolio-plan.md`'s interview checklist expects a
concrete answer to both "what happens if the database commit succeeds but Kafka publishing fails?" and
"how does the outbox pattern improve that?"

## Decision

Implement the transactional outbox in Order Service in Phase 6, and until then treat the failure window
as a known, documented limitation.

**Phase 1–5 (current): publish after commit.** Persist, then publish. The window exists. It is
documented here, in `docs/events/event-catalog.md` §2, and must not be described anywhere as durable
event publication.

**Phase 6: outbox in Order Service.** The business change and an `outbox_events` insert commit in one
local transaction; a separate publisher polls pending rows, sends them to Kafka, and marks them
published (`docs/db-ownership.md`).

```text
outbox_events
-------------
id, aggregate_id, event_type, payload (full envelope), created_at, published_at, status
```

Scope is deliberately one service. `docs/planning/sprint-1/implementation-phases.md`'s Phase 6 says "at least the
most important publisher, likely Order Service", and Order Service is the right choice: it is the only
publisher whose lost event strands an order that a user has already been told was accepted.

> **Correction, Phase 6 (implementation).** This section originally continued: "The other publishers
> lose an event that a redelivery can regenerate, because their publishes are themselves reactions to
> consumed events — if `InventoryReserved` is lost, the `OrderCreated` that caused it can be
> reprocessed." That is **not true of this implementation**, and the mistake matters. Every
> event-driven publish site in all four services claims its `processed_events` row *inside* the
> business transaction (ADR-005 requires exactly that), so a redelivery is short-circuited by the
> ledger before it can republish anything: the event is not regenerated, it is silently skipped. A
> crash between such a commit and its publish strands the order just as permanently as a lost
> `OrderCreated` does — only at a later status. Phase 6 therefore routed **both** of Order Service's
> publish sites through the outbox (`OrderCreated` from `POST /api/orders`, and `PaymentRequested`
> from the `InventoryReserved` transition), not just the first. The same reasoning applies to
> Inventory, Payment and Fulfillment Service, which remain out of scope and therefore still carry a
> real, non-self-healing dual-write window — see `docs/agent-reports/phase-6-outbox.md` §Judgment
> calls. What redelivery does still cover is the narrower case of a consumer that crashes *before*
> committing anything at all.

## Alternatives considered

**Do nothing; keep publish-after-commit permanently.** Simplest, and honestly adequate for a demo whose
processes rarely die at the wrong microsecond. Rejected because the dual-write problem is one of the
project's headline talking points, and demonstrating the *fix* is worth considerably more than
describing the problem. It also stays available as the fallback for the three services outside Phase 6's
scope.

**Publish before commit.** Removes the lost-event case by introducing a phantom-event case: consumers
act on a state change the publisher then rolls back. Rejected as strictly worse — a lost event leaves an
order stuck, while a phantom event corrupts other services' state.

**Kafka transactions spanning the database write.** Not possible: Kafka transactions are atomic within
Kafka. They cannot enroll a PostgreSQL commit, which is the whole difficulty.

**Two-phase commit / XA across PostgreSQL and Kafka.** The textbook answer, and Kafka does not support
it. Even where XA is available it is operationally painful (blocking coordinator, in-doubt
transactions), and `docs/planning/project-overview.md` treats distributed transactions as out of scope.

**Change Data Capture with Debezium** — tail the PostgreSQL WAL and derive events from row changes.
Genuinely strong, no application-level publisher, and no dual write at all. Rejected for this project on
two grounds: it adds Kafka Connect plus a connector to operate, which
`docs/planning/project-overview.md`'s Scope Principles push back on ("do not add infrastructure unless a
concrete need emerges"); and CDC events are shaped by table structure, so producing the deliberate event
envelope in `docs/events/event-catalog.md` would require an extra transformation step. It would also
hide the mechanism the project is trying to demonstrate — the outbox's value here is partly pedagogical.
Worth naming as the answer for a real system with many publishers.

**Polling the business tables instead of an outbox** — find orders in `PENDING` with no published event
and publish for them. No new table. Rejected because it needs a per-aggregate notion of "event already
published" (which is what the outbox is), it does not generalize past the first event type, and it
cannot preserve publication order.

## Consequences and tradeoffs

**As built (Phase 6).** The list below was written in Phase 0 and held up; these are the points where
implementation pinned down something the prose left open (full detail in
`docs/agent-reports/phase-6-outbox.md`):

- Both Order Service publish sites go through the outbox, for the reason in the Decision correction
  above. `OrderCreated` is recorded by `OrderPersistence#createPendingOrder`, `PaymentRequested` by
  `OrderPersistence#appendInventoryReservedTransition` — the same transactions that already commit the
  business rows and, for the second, the `processed_events` claim.
- Polling only, no notify-on-commit hook: the interval is a property
  (`orderfulfillment.outbox.poll-interval-ms`) defaulting to 50 ms, which is inside the latency budget
  below without a second concurrent path into the publisher.
- Retries are bounded by row age, not by a counter, because the frozen schema has no retry-count
  column: a failing send leaves the row `PENDING` and stops the batch (ordering), and a row that is
  still unpublished `orderfulfillment.outbox.fail-after-ms` after it was written (default 5 min) is
  marked `FAILED`, logged at ERROR, and skipped so it cannot block the queue forever.
- The envelope is built and serialized at business-transaction time, so `eventId`, `occurredAt` and
  `correlationId` describe the business event rather than the send attempt, and are stable across
  resends. The `payload` column being `jsonb` means PostgreSQL normalizes that text (key order,
  whitespace), so the publisher re-serializes it compactly on the way out; the document is unchanged.

**While unimplemented (Phases 1–5).**

- A crash between commit and publish strands an order with no event. Nothing detects it automatically;
  the order sits at `PENDING` forever.
- This must be stated, not implied, in the README and architecture page. "Durable event publication" is
  not a claim this project may make before Phase 6
  (`docs/planning/agent-guidance.md` rule 18).

**Once implemented (Phase 6, Order Service).**

- Publication latency gains a polling interval. At demo scale, tens of milliseconds if the publisher
  polls tightly, and it can be pushed lower with a notify-on-commit hook.
- A new failure mode replaces the old one: the publisher can crash *after* Kafka accepted the record but
  *before* marking the row published, so the row is resent. That is a duplicate, which is exactly what
  the idempotent consumers in ADR-005 already handle — the outbox converts a lost-event problem into a
  duplicate-event problem, and duplicates are the one this project has already solved.
- More moving parts to explain and to operate: an extra table, a background publisher, and a `status`
  column whose `FAILED` rows need someone to look at them.
- Ordering: rows must be published in insertion order per aggregate to preserve the per-partition
  ordering ADR-001 relies on.
- Asymmetry becomes a documentation obligation. After Phase 6, Order Service publishes durably and the
  other three do not; the architecture page must say which is which rather than implying the whole
  system is covered.
