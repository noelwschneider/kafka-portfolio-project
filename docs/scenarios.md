# Demo Scenarios

**Status:** frozen by Phase 0. Source: `docs/planning/frontend-design.md`'s Required Demo Scenarios
section (behavior, what each demonstrates, success conditions) and
`docs/planning/backend-design.md` 4.6 (endpoint names).

Every scenario is triggered by `POST /demo/scenarios/{scenarioName}` on Scenario Service and returns
a `runId`; progress is read from `GET /demo/scenario-runs/{runId}` or streamed from
`GET /demo/scenario-runs/{runId}/stream`. The full contract is `docs/openapi/scenario-service.yaml`.

Two rules govern everything below:

- **Scenario behavior is real.** Each scenario drives genuine HTTP requests, genuine Kafka records,
  and genuine persistence. Nothing here is a frontend animation, and no scenario-specific branch is
  allowed inside `/api` business logic (`docs/planning/agent-guidance.md` rules 9 and 10).
- **Scenarios use the normal APIs.** A scenario creates orders through `POST /api/orders`, the same
  endpoint any client uses. What a scenario may do beyond that is configure a service's demo-only
  controls (payment behavior, consumer pause) or republish a record — never take a shortcut through
  the domain.

Seed data referenced below is from `docs/planning/backend-design.md`'s Seed Data section:
SKU-001: 10, SKU-002: 5, SKU-003: 100, SKU-004: 2. Prices are in `docs/db-ownership.md`.

---

## Index

| # | Scenario | Endpoint | Terminal state / success condition | Lands in |
|---|---|---|---|---|
| 1 | Standard Fulfillment | `POST /demo/scenarios/standard-order` | `FULFILLED` | Phase 1, via Kafka in Phase 2 |
| 2 | Out of Stock | `POST /demo/scenarios/out-of-stock` | `REJECTED_OUT_OF_STOCK` | Phase 1, via Kafka in Phase 2 |
| 3 | Payment Rejection | `POST /demo/scenarios/payment-failure` | `PAYMENT_FAILED` + reservation released | Phase 1, compensation via Kafka in Phase 2 |
| 4 | Duplicate Event Delivery | `POST /demo/scenarios/duplicate-event` | No duplicate side effect | Phase 4 |
| 5 | Consumer Outage and Recovery | `POST /demo/scenarios/consumer-outage` | Backlog processed after resume | Phase 4 |
| 6 | Poison Message / DLQ | `POST /demo/scenarios/poison-message` | Record lands in the expected DLQ | Phase 4 |
| 7 | Inventory Contention | `POST /demo/scenarios/inventory-contention` | Reserved never exceeds available | Phase 4 |
| 8 | High-Volume Batch | `POST /demo/scenarios/high-volume` | Throughput/lag observable | Phase 10 |

Plus `POST /demo/reset`, which is not a scenario: it restores seed inventory, clears demo state, and
resets any consumer pause or payment-behavior override left behind by a run.

---

## Scenario 1 — Standard Fulfillment

**Endpoint:** `POST /demo/scenarios/standard-order`

**Behavior.** Create an order with available inventory and successful payment.

**What the backend actually does.** Scenario Service ensures payment behavior is `DEFAULT_SUCCESS`,
then `POST /api/orders` for 2 × SKU-001 (10 in stock). The order flows `PENDING` →
`INVENTORY_RESERVED` → `PAYMENT_PENDING` → `PAID` → `FULFILLMENT_PENDING` → `FULFILLED` through
`OrderCreated`, `InventoryReserved`, `PaymentRequested`, `PaymentAuthorized`, `ShipmentCreated`.

**Demonstrates.** REST request, persistence, event publication, Kafka consumption, asynchronous
workflow, state transitions.

**Expected terminal state.** `FULFILLED`

**Observable proof.** The run timeline shows the HTTP 201 returning *before* the downstream events —
the visible evidence that `POST /api/orders` does not wait for fulfillment.

---

## Scenario 2 — Out of Stock

**Endpoint:** `POST /demo/scenarios/out-of-stock`

**Behavior.** Create an order requesting more inventory than exists.

**What the backend actually does.** `POST /api/orders` for 5 × SKU-004 (2 in stock). Inventory
Service finds the line unsatisfiable and publishes `InventoryReservationFailed` with a `shortages`
entry; Order Service moves the order to `REJECTED_OUT_OF_STOCK`. No payment is requested and no
reservation is held, so there is nothing to compensate.

**Demonstrates.** Domain validation, inventory ownership, rejection events, asynchronous failure
propagation.

**Expected terminal state.** `REJECTED_OUT_OF_STOCK`

**Note.** The rejection is a business outcome, not a fault: nothing is retried and nothing is
dead-lettered. Contrast with Scenario 6, where the failure *is* a fault.

---

## Scenario 3 — Payment Rejection

**Endpoint:** `POST /demo/scenarios/payment-failure`

**Behavior.** Inventory reserves successfully; the payment simulator rejects authorization.

**What the backend actually does.** Scenario Service arms the rejection first — `PUT
/demo/payment-behavior` with `mode: REJECT` on Payment Service — then `POST /api/orders` for
1 × SKU-001, and clears the override once the run finishes. Inventory reserves; Order Service
publishes `PaymentRequested`; Payment Service publishes `PaymentRejected`. Two consumers react
independently: Order Service moves the order to `PAYMENT_FAILED`, and Inventory Service releases the
reservation and publishes `InventoryReleased`.

The override is armed *before* the order exists, and therefore un-scoped: the order id isn't known
until `POST /api/orders` returns, and arming it afterwards would race the consumer. So the override
applies to all payment requests for the duration of the run — deterministic for a single reviewer,
and the cost is that an order created concurrently with this scenario would also be rejected. The
alternative, scoping by order id, is available on the endpoint for targeted manual use.

**Demonstrates.** Downstream business failure, compensation, inventory release, eventual state
correction.

**Expected terminal state.** `PAYMENT_FAILED`, with the reservation released and SKU-001's available
quantity back to its pre-order value.

**Observable proof.** `InventoryReleased` in the timeline, and inventory returning to its starting
level. This is the project's saga-like compensation step, so the release must be visible rather than
merely happening.

---

## Scenario 4 — Duplicate Event Delivery

**Endpoint:** `POST /demo/scenarios/duplicate-event`

**Behavior.** Deliver the same logical event twice.

**What the backend actually does.** Run a normal order, then republish one of its records —
`OrderCreated` — to `orders.events` with the **same `eventId`**, the same key, and the same payload.
Inventory Service's consumer finds the `(event_id, consumer_name)` row already present in
`processed_events`, acknowledges, and applies no second reservation.

**Demonstrates.** At-least-once processing assumptions, event IDs, idempotent consumers, duplicate
detection.

**Success condition.** The duplicate produces no duplicate reservation, payment, or shipment.
Concretely: one `inventory_reservations` row for the order, one `payment_attempts` row, one
`shipments` row, and no extra `order_status_history` rows.

**Observable proof.** The timeline shows the event consumed twice and the side effect applied once —
which is the whole point, and is why the duplicate must be a real republish rather than a UI label.

---

## Scenario 5 — Consumer Outage and Recovery

**Endpoint:** `POST /demo/scenarios/consumer-outage`

**Behavior.** Temporarily stop a consumer, publish work, then restore processing.

**What the backend actually does.** Scenario Service pauses Inventory Service's `OrderCreated`
listener through that service's demo control, creates one or more orders, waits so the backlog is
observable (orders sit at `PENDING` while their events are durably queued), then resumes the
listener. Kafka retains the records; on resume the consumer continues from its committed offset and
the orders complete normally.

**Demonstrates.** Kafka durability, offsets, asynchronous decoupling, consumer recovery.

**Success condition.** The pending event is processed after the consumer becomes available again,
and the affected orders reach `FULFILLED`.

**Note.** The pause is a genuine Spring Kafka listener-container pause, not a discarded message or a
simulated delay — `docs/planning/backend-design.md` 4.6 requires the outage to "genuinely
disable/stop/restart relevant processing".

---

## Scenario 6 — Poison Message / DLQ

**Endpoint:** `POST /demo/scenarios/poison-message`

**Behavior.** Publish an event that repeatedly fails processing.

**What the backend actually does.** Scenario Service publishes a deliberately unprocessable record to
`inventory.events`' inbound counterpart — a well-formed envelope whose payload cannot be applied
(for example an unparseable `payload` body, or an `eventVersion` no consumer knows). The consumer's
error handler applies bounded retries with backoff, exhausts them, and routes the record to
`inventory.dlq` together with its failure metadata.

**Demonstrates.** Retry policy, backoff, bounded failure, dead-letter routing, operational
troubleshooting.

**Success condition.** The record ends in the expected DLQ and is visible in the UI, with the error
inspectable and the retry count shown.

**Note.** A poison record is not necessarily tied to a live order. When it is, that order ends in
`FAILED` (`docs/order-state-machine.md` transition 9); when it is not, no order status changes and
only the DLQ shows the outcome.

---

## Scenario 7 — Inventory Contention

**Endpoint:** `POST /demo/scenarios/inventory-contention`

**Behavior.** Create several simultaneous orders for stock that cannot satisfy all requests.

**What the backend actually does.** Two concurrent `POST /api/orders` calls, each for 2 × SKU-004,
against SKU-004's seeded stock of 2. Both orders are accepted over HTTP; their `OrderCreated` events
are processed against `inventory_items` under optimistic locking (`version`), so exactly one
reservation succeeds. The winner reaches `FULFILLED`; the loser reaches `REJECTED_OUT_OF_STOCK`.

**Demonstrates.** Concurrent access, transaction isolation, locking/versioning, consistency under
contention.

**Success condition.** Total reserved inventory never exceeds available inventory — `reserved_quantity
<= 2` for SKU-004 at every point, and never two successful reservations.

**Notes.**

- SKU-004's stock of 2 is the frozen fixture, because
  `docs/planning/backend-design.md`'s Seed Data section states that quantity exists specifically to
  make this scenario "trivial to trigger with two concurrent small orders".
  `docs/planning/frontend-design.md` illustrates the scenario with stock 5 and two orders of 4, which
  maps onto SKU-002 instead; the two are equivalent in kind, and SKU-004 is the default.
- `docs/planning/execution-plan.md` §2 assigns the reservation/concurrency logic behind this scenario
  its highest scrutiny tier, because a subtle bug here silently oversells stock and invalidates the
  project's core reliability claim. The database `CHECK (available_quantity >= 0)` in
  `docs/db-ownership.md` is the backstop, not the mechanism.

---

## Scenario 8 — High-Volume Batch

**Endpoint:** `POST /demo/scenarios/high-volume`

**Behavior.** Generate many orders quickly.

**What the backend actually does.** Scenario Service issues a burst of `POST /api/orders` calls for
1 × SKU-003 (100 in stock, chosen so the burst needs no artificial restocking) and records
throughput, processing latency, and consumer lag as the backlog drains across consumer-group
replicas.

**Demonstrates.** Event throughput, consumer groups, horizontal scaling, lag/processing behavior,
Kubernetes scaling if an HPA is configured.

**Success condition.** Throughput and lag are observable, and orders reach `FULFILLED` without loss.

**Note.** Explicitly a later-stage scenario (`docs/planning/frontend-design.md`), landing with Phase
10's scaling demonstration. The burst size is left to Phase 10, where it can be tuned against real
measurements; the seeded 100 units of SKU-003 bound it at 100 orders of one unit before a reset is
needed.

---

## `POST /demo/reset`

Not a scenario. Restores the demo environment to its seeded starting point: seed inventory
quantities, cleared demo state, released consumer pauses, and payment behavior back to
`DEFAULT_SUCCESS`. Provided so a reviewer can re-run any scenario from a known state, and so a
scenario that failed halfway cannot leave a paused consumer or a rejecting payment simulator behind
to break the next one.

Whether reset also deletes historical orders and scenario runs is a Phase 4/5 implementation
decision, not frozen here: the argument for keeping them is that the Event Explorer and run history
are the demo's evidence trail.
