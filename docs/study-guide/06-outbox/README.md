# Chapter 6 — The transactional outbox

**Build history:** Phase 6 — `a919731 transactional outbox`, Order Service only. Extended to the other
three services in Sprint 2 — `a045fe8`.

The shortest chapter with the highest ratio of insight to code. Three small classes and one table
close a failure mode that has no error message, no failed request, and no alert — an order that
silently never progresses because the event announcing it was never sent.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [The dual-write problem](1-the-dual-write-problem.md) | The window and why it is silent, why publishing first is worse, the mistake ADR-006 made about redelivery self-healing, and the alternatives worth naming |
| 2 | [The implementation](2-the-implementation.md) | The table, building the envelope at transaction time, ordered serial dispatch, duplicates-not-losses, age-bounded retry, the `jsonb` round-trip, and four tuned numbers |
| 3 | [The rollout](3-the-rollout.md) | Why one service first, how ADR-006 recorded its own correction, what Sprint 2 changed in each service, and the duplication nobody decided about |

---

## The one sentence to remember

> A lost-event problem becomes a duplicate-event problem, and duplicates are the one thing
> ADR-005's idempotent consumers already handle.

The outbox does not give you exactly-once delivery. **It converts an unhandleable failure into one you
already solved.** Anyone who describes an outbox as achieving exactly-once has skipped this step — and
being able to say precisely what it does instead is the point of the chapter.

## The second-order lesson

[Section 1](1-the-dual-write-problem.md) contains the best example in the project of two correct
designs interacting badly.

ADR-005 requires the idempotency claim to commit **inside** the business transaction. Correct.
ADR-006 originally scoped the outbox to one service, on the reasoning that the other three would
self-heal through redelivery. Plausible.

Together they are wrong: the ledger claim short-circuits the redelivery, so the "self-healing" never
happens and the order strands permanently at a later status instead of at `PENDING`.

**Neither ADR is individually incorrect. The interaction was unexamined** — and it was not visible
from either document alone. That is worth more as a general lesson than the outbox pattern itself.

---

## Build it yourself

Per service — four services, identically.

1. `V4__outbox_events.sql` (`V6` in Inventory, which already had five): `id bigserial`,
   `aggregate_id text`, `event_type text`, `payload jsonb`, `created_at timestamptz`,
   `published_at timestamptz NULL`, `status text`.
2. `OutboxEventEntity`, `OutboxEventRepository` (with `findByStatusOrderByIdAsc(status, Pageable)`),
   and an `OutboxStatus` of `PENDING`/`PUBLISHED`/`FAILED`.
3. **`OutboxRecorder`** — `@Transactional(propagation = MANDATORY)`, building the envelope via
   `EventPublisher.buildEnvelope` **at transaction time**, serializing it into `payload`, and
   returning the `eventId` (with the caller-supplied-id overload for `PaymentRequested`).
4. **`OutboxDispatcher`** — `@Transactional`, `findByStatusOrderByIdAsc` with `FOR UPDATE`, sending
   **one row at a time** and blocking on each acknowledgement with `.get(timeout)`, marking each
   `PUBLISHED`. On failure: `markFailed` + `ERROR` + `continue` if the row has aged past
   `fail-after-ms`, otherwise `WARN` + **`break`** so the topic is not reordered. Re-serialize the
   `jsonb` on the way out.
5. **`OutboxPublisher`** — a *separate bean* carrying
   `@Scheduled(fixedDelayString = "${…poll-interval-ms:50}")`, with a narrow `catch` around the call.
6. Configuration: `poll-interval-ms: 50`, `batch-size: 100`, `send-timeout-ms: 10000`,
   `fail-after-ms: 300000` — each with a comment saying what it bounds.
7. **Replace every publish site.** `EventPublisher.publish` → `OutboxRecorder.record`, inside the
   existing `REQUIRES_NEW` transaction that already holds the business change and the ledger claim:
   - Order Service — `createPendingOrder` (`OrderCreated`) and
     `appendInventoryReservedTransition` (`PaymentRequested`)
   - Inventory Service — `attemptReserve` and `release`
   - Payment Service — `authorize`
   - Fulfillment Service — `createShipment`
8. **Remove publishing from the consumers entirely.** A listener should no longer send anything.
9. `*OutboxIntegrationTest` per service: assert the business row and the `outbox_events` row commit
   together; assert the row reaches `PUBLISHED` and the record appears on the topic with the **same
   `eventId`** the transaction recorded; assert a row older than `fail-after-ms` whose send fails is
   marked `FAILED` and does not block later rows.

**Done when:** every publish site in every service writes to `outbox_events` rather than to Kafka
directly; no listener sends anything; killing a service between its commit and the next dispatcher tick
loses nothing (the row publishes on restart); and the events on the wire are byte-for-byte the
envelopes the business transactions recorded.

---

## Next

[Section 1 — The dual-write problem](1-the-dual-write-problem.md).
