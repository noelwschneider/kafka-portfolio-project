# 5.3 — The eight scenarios

[← Server-Sent Events](2-server-sent-events.md) · [Next: Observing the system →](4-observing-the-system.md)

The scenarios *are* the portfolio. Everything else in the project exists so that these eight can be
real.

---

## What "real" means, operationally

Each scenario is one `@Component` implementing `ScenarioRunner`. Three rules apply without exception:

1. **Orders are created through `POST /api/orders`** — the same endpoint any client uses, with no
   scenario parameter.
2. **Failure conditions are configured through `/demo` endpoints**, before the order exists. The
   business path never knows why it is failing.
3. **Outcomes are observed, never assumed.** No scenario sleeps for a plausible duration and declares
   success.

---

## 1 — Standard Fulfillment → `FULFILLED`

The happy path. Set payment behavior to `DEFAULT_SUCCESS`, create an order, wait for a terminal state.

The value is not the outcome, which is unsurprising. It is the **timeline**: `POST /api/orders`
returning `201` first, then `OrderCreated`, `InventoryReserved`, `PaymentRequested`,
`PaymentAuthorized`, `ShipmentCreated` arriving after it, with sub-second timestamps.

That ordering *is* the demonstration. The HTTP response and the work are visibly separate things.

## 2 — Out of Stock → `REJECTED_OUT_OF_STOCK`

Order more of a SKU than exists. `SKU-002` is seeded at 5 for this.

Demonstrates a **business rejection with nothing to compensate**: no payment was attempted, so there
is nothing to undo. The contrast with Scenario 3 is the point of having both.

## 3 — Payment Rejection → `PAYMENT_FAILED` + stock released

Arm `PUT /demo/payment-behavior` with `REJECT`, then create an order.

The most architecturally interesting of the first three, because the interesting part happens *after*
the order reaches its terminal state. `PaymentRejected` goes to `payments.events`; Inventory Service
consumes it independently and releases the reservation, publishing `InventoryReleased`.

**Compensation, visible.** There is no transaction to roll back, so undoing the reservation is an
explicit event-driven step performed by a different service — and the timeline shows it happening
after the order was already `PAYMENT_FAILED`.

This scenario also carries ADR-002's acknowledged wart:

> Payment Service's rejection override is armed before its target order exists, so it is un-scoped for
> the duration of a run.

The honest cost of not passing a flag through the business request.

## 4 — Duplicate Event Delivery → no duplicate side effect

Run a normal order, then republish its real `OrderCreated` record — same `eventId`, same key, same
bytes:

> a genuine second Kafka record, not a UI label, so Inventory Service's own idempotency check (its
> `processed_events` ledger) is what actually suppresses the second reservation.

The scenario first **waits for its own event to appear in the projection**, so it republishes the
actual record rather than a reconstruction — and throws with a clear message if it never does.

Note the dependency on a Phase 0 envelope rule: *"a duplicate delivery of the same logical event
reuses the same `eventId`."* Without that, this scenario could not exist, and neither could
deduplication.

## 5 — Consumer Outage and Recovery → backlog processed after resume

Pause Inventory Service's listener via `/demo/consumers/{id}/pause`, create orders, watch them sit at
`PENDING`, resume, watch them complete.

A **genuine listener-container pause** ([Chapter 4](../04-reliability/5-pausing-consumers.md)) — not
dropped records, not a simulated delay. The records stay on the topic and the offset stays put.

The observable that makes it land is **consumer lag** ([section 4](4-observing-the-system.md)), read
from the broker rather than counted by the application: it climbs while paused, drains on resume.

## 6 — Poison Message / DLQ → record lands in the expected DLQ

Publish a record that cannot be processed — a bad `eventVersion`, or a payload that will not
deserialize — via a raw `KafkaTemplate`, because `EventPublisher` deliberately cannot produce one.

Demonstrates the whole failure path from [Chapter 4](../04-reliability/2-retry-and-dlq.md):
classification as non-retryable, immediate dead-lettering rather than four blocked deliveries, and
failure metadata a human can read. Since Sprint 2 it also demonstrates the consequence — the order
moves to `FAILED` rather than sitting at whatever status it had reached.

`x-delivery-attempts: 1` on the DLQ record is the tell that the **non-retryable** path was taken. A
retryable failure would show 4.

## 7 — Inventory Contention → reserved never exceeds available

Several orders race for `SKU-004`, seeded at 2 precisely for this.

The success condition is an **invariant**, not an outcome: some orders succeed, some are rejected, and
`reserved ≤ available` holds throughout. Which orders win is genuinely nondeterministic, and a
scenario asserting a specific winner would be asserting a race.

This is the visible face of [Chapter 4](../04-reliability/3-inventory-contention.md)'s optimistic-lock
retry loop — and the integration tests behind it additionally assert the conflict counter is non-zero,
so a run that happened not to race cannot pass silently.

## 8 — High-Volume Batch → throughput and lag observable

Create many orders quickly and report real throughput, latency, and consumer lag.

The only scenario whose success condition is a **measurement** rather than a state. It is what
[Chapter 8](../08-observability-and-scaling/README.md) uses to show what adding consumer replicas
actually does — and, just as usefully, what it does not do past three partitions.

---

## `POST /demo/reset` — not a scenario

Restores seed inventory, clears demo state, and resets any consumer pause or payment-behavior override
a run left behind.

It exists because of ADR-002's most under-appreciated line:

> **Demo state is real state.** A run that fails halfway can leave a paused listener or an armed
> rejection behind, which is why `POST /demo/reset` exists and why it reports what it actually reset.

**And why it reports what it actually reset** — the response says what was done rather than returning
an unconditional success.

Reset is also why `restoreForDemo` exists on Inventory Service as a separate operation from the
business `PUT`, and the reasoning is a nice illustration of a demo endpoint being *correctly* different
rather than sloppily so:

> Deliberately bypasses the `availableQuantity >= reservedQuantity` guard that
> `updateAvailableQuantity` enforces: reservations are only released on the payment-failure
> compensation path (never on successful fulfillment), so `reservedQuantity` accumulates without bound
> over a long-running demo and will routinely exceed any seed value. The production PUT correctly
> rejects that as an oversold state; this demo-only endpoint's whole job is to atomically zero both
> fields together so a "reset" actually means what it says.

The business rule is right, and inapplicable to a reset. Rather than weakening the business rule, a
demo-only operation with its own semantics lives under `/demo`. That is the `/api`–`/demo` split
earning its keep on a case nobody anticipated in Phase 0.

> **We got this wrong.** `restoreForDemo` did not exist initially, and reset used the business PUT —
> which meant reset silently failed with a 409 once `reservedQuantity` had accumulated, wedging the
> live demo. Commit `1a81745`. [Chapter 10](../10-retrospective/README.md).

An **idle reset scheduler** also runs reset automatically after 15 minutes of inactivity, so a public
demo left in a strange state by one visitor is clean for the next
([Chapter 9](../09-production/README.md)).

---

## Testing them

Phase 4's exit criterion again: *"Each advertised failure scenario is backed by an automated
integration test."*

Every scenario has one — `StandardOrderScenarioIntegrationTest`,
`DuplicateEventScenarioIntegrationTest`, `PoisonMessageScenarioIntegrationTest`, and so on — plus
`ScenarioConflictIntegrationTest` for concurrent-run handling and `DemoResetIntegrationTest` for
reset.

These live in Scenario Service and exercise the **orchestration**: that a run reaches `COMPLETED`,
that the timeline contains the expected entries in the expected order, and that the primary order
reaches the expected terminal status. The *mechanisms* — idempotency, DLQ routing, the contention
invariant — are tested in the services that own them
([Chapter 4](../04-reliability/README.md)).

**Two layers testing two different things.** A scenario test failing means the demonstration is
broken; a mechanism test failing means the system is. Keeping them separate means a failure tells you
which.

---

[← Server-Sent Events](2-server-sent-events.md) · [Next: Observing the system →](4-observing-the-system.md)
