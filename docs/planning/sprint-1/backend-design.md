# 4. Recommended System Architecture

The final target should contain approximately four backend services plus infrastructure.

## Core services

### 4.1 Order Service

Responsibilities:

- expose the primary order REST API,
- create orders,
- validate order requests,
- persist order state,
- publish `OrderCreated`,
- consume relevant downstream events,
- update the overall order lifecycle,
- expose order status/history.

Suggested ownership:

- `orders`
- `order_items`
- optional order event projection/history

Primary API examples:

```http
POST /api/orders
GET /api/orders/{orderId}
GET /api/orders
```

---

### 4.2 Inventory Service

Responsibilities:

- own product stock,
- reserve inventory,
- reject reservations when stock is insufficient,
- release inventory where required,
- protect stock from concurrent over-allocation,
- consume `OrderCreated`,
- publish inventory outcome events.

Primary events:

- consumes `OrderCreated`
- publishes `InventoryReserved`
- publishes `InventoryReservationFailed`

Optional management API:

```http
GET /api/inventory
GET /api/inventory/{sku}
PUT /api/inventory/{sku}
```

The management API is primarily for setup/demo administration rather than order orchestration.

---

### 4.3 Payment Service / Payment Simulator

This is intentionally a simulator. No real payment provider should be required.

Responsibilities:

- consume a request to authorize payment,
- produce deterministic or scenario-controlled success/failure,
- model retryable and non-retryable payment failures,
- preserve idempotency.

Primary events:

- consumes `PaymentRequested`
- publishes `PaymentAuthorized`
- publishes `PaymentRejected`

Optional behavior configuration:

- default success,
- reject a configured order,
- throw a retryable simulated provider error.

---

### 4.4 Fulfillment Service

Responsibilities:

- consume authorized order events,
- create a shipment/fulfillment record,
- publish shipping completion event,
- expose fulfillment status if useful.

Primary events:

- consumes `PaymentAuthorized`
- publishes `ShipmentCreated`

Optional later event:

- `OrderShipped`

---

## Optional 4.5 Notification Service

Only add this after the core system is stable.

Responsibilities:

- consume lifecycle events,
- create simulated customer notifications,
- store or log notification history.

This service is useful because it demonstrates fan-out: one event can be consumed independently by multiple services.

Do not make actual email delivery a requirement.

---

## 4.6 Demo / Scenario Control Layer

Keep this isolated from production-style business logic.

This may be:

- a small dedicated Spring Boot service, or
- a clearly separated demo-only module/API within an existing service during early iterations.

Recommended long-term direction: **dedicated Scenario Service**.

Example endpoints:

```http
POST /demo/scenarios/standard-order
POST /demo/scenarios/out-of-stock
POST /demo/scenarios/payment-failure
POST /demo/scenarios/duplicate-event
POST /demo/scenarios/consumer-outage
POST /demo/scenarios/inventory-contention
POST /demo/scenarios/poison-message
POST /demo/scenarios/high-volume
POST /demo/reset
```

The scenario layer must trigger real backend behavior.

Examples:

- Duplicate event scenario genuinely republishes an event with the same logical event ID.
- Inventory contention genuinely creates concurrent reservation attempts.
- Payment failure configures the payment simulator to reject a request.
- Poison message publishes an invalid or intentionally unprocessable message.
- Consumer outage genuinely disables/stops/restarts relevant processing where practical.

---

# 5. Event-Driven Order Lifecycle

A recommended happy-path lifecycle:

```text
Client
  |
  | POST /api/orders
  v
Order Service
  |
  | persist order: PENDING
  | publish OrderCreated
  v
Kafka
  |
  v
Inventory Service
  |
  | reserve stock
  | publish InventoryReserved
  v
Kafka
  |
  v
Order Service / Payment workflow
  |
  | publish PaymentRequested
  v
Kafka
  |
  v
Payment Service
  |
  | authorize simulated payment
  | publish PaymentAuthorized
  v
Kafka
  |
  v
Fulfillment Service
  |
  | create shipment
  | publish ShipmentCreated
  v
Kafka
  |
  v
Order Service
  |
  | update order: FULFILLED
  v
Client / event stream
```

A failed inventory flow:

```text
OrderCreated
  |
  v
Inventory Service
  |
  | insufficient stock
  v
InventoryReservationFailed
  |
  v
Order Service
  |
  v
order status = REJECTED_OUT_OF_STOCK
```

A failed payment flow:

```text
InventoryReserved
  |
  v
PaymentRequested
  |
  v
PaymentRejected
  |
  +--> Order Service -> PAYMENT_FAILED
  |
  +--> Inventory Service -> release reservation
```

This introduces a simple compensation workflow and creates a useful opportunity to discuss saga-like behavior without needing to implement an elaborate orchestration framework.

---

# 6. Event Design

All domain events should use an explicit envelope.

Example:

```json
{
  "eventId": "0c7c3acd-8b3b-45fd-ae4a-b8c73b5a419e",
  "eventType": "OrderCreated",
  "eventVersion": 1,
  "occurredAt": "2026-08-07T20:31:04.220Z",
  "correlationId": "d89512f7-b544-4170-b66b-2e93f475ea8f",
  "aggregateId": "order-21873",
  "payload": {
    "orderId": "order-21873",
    "customerId": "demo-customer",
    "items": [
      {
        "sku": "SKU-001",
        "quantity": 2
      }
    ]
  }
}
```

## Required event metadata

- `eventId`
- `eventType`
- `eventVersion`
- `occurredAt`
- `correlationId`
- `aggregateId`
- `payload`

## Why these fields matter

### eventId
Used for idempotency / duplicate detection.

### eventType
Allows consumers and tooling to identify the semantic event.

### eventVersion
Creates a deliberate point for discussing schema evolution.

### occurredAt
Supports timelines and debugging.

### correlationId
Allows related activity across services to be traced as one workflow.

### aggregateId
Provides a natural Kafka message key, such as `orderId`, so events for the same order can preserve per-partition ordering.

---

# 7. Kafka Topic Strategy

Start simple.

Possible topics:

```text
orders.events
inventory.events
payments.events
fulfillment.events
demo.events
orders.dlq
inventory.dlq
payments.dlq
fulfillment.dlq
```

An alternative is to use more event-specific topics, but that increases operational overhead.

For an early portfolio build, domain-oriented topics are easier to operate and explain.

Use the relevant aggregate ID as the Kafka record key where ordering matters.

Example:

```text
key = orderId
```

## Concepts the implementation should demonstrate

- producers,
- consumers,
- consumer groups,
- partitions,
- keyed messages,
- at-least-once delivery assumptions,
- idempotent consumers,
- retries,
- dead-letter topics,
- consumer recovery,
- message ordering within a partition,
- offset-based consumption.

Do not advertise "exactly once" unless the implementation truly establishes the required semantics end-to-end.

---

# 8. Reliability Patterns

## 8.1 Idempotent consumers

Each consumer should be able to safely receive the same event more than once.

Recommended implementation:

Create a table such as:

```text
processed_events
----------------
event_id
consumer_name
processed_at
```

Before applying side effects:

1. check whether the event has already been processed by that logical consumer,
2. if yes, acknowledge/ignore it,
3. if no, perform the business operation and record the event ID transactionally where feasible.

The duplicate-event scenario should visibly prove this behavior.

---

## 8.2 Retry behavior

Distinguish:

### Retryable errors

Examples:

- temporary simulated dependency failure,
- transient database error,
- temporary service issue.

Behavior:

- retry with bounded attempts,
- use backoff,
- send to DLQ after maximum attempts.

### Non-retryable errors

Examples:

- invalid event schema,
- impossible domain data,
- known business rejection.

Behavior:

- do not infinitely retry,
- fail explicitly,
- route to a dead-letter/error handling path if appropriate.

---

## 8.3 Dead-letter queue

The project should contain at least one demonstrable DLQ workflow.

The UI should allow a reviewer to:

1. inject a poison event,
2. watch retries occur,
3. see the event move to a DLQ,
4. inspect the error,
5. optionally retry/replay the dead-lettered event after correction.

---

## 8.4 Transactional event publishing

A major design concern is:

> What happens if the service commits its database transaction but fails before publishing its Kafka event?

For the first rendition, it is acceptable to document this explicitly as a known consistency problem.

A stronger later implementation should use the **transactional outbox pattern**.

Recommended progression:

### Initial MVP
Persist entity, then publish event.

Document the failure window.

### Stronger version
Use:

```text
business table update
+
outbox table insert
```

in one database transaction.

A publisher then sends pending outbox records to Kafka and marks them delivered.

Potential table:

```text
outbox_events
-------------
id
aggregate_id
event_type
payload
created_at
published_at
status
```

Implementing the outbox pattern would significantly strengthen the project's backend/system-design value.

---

# 9. PostgreSQL Data Model

Each service should ideally own its database/schema boundaries.

For local development, these may share one PostgreSQL server but use separate databases or schemas.

## Order Service

### orders

```text
id
customer_id
status
total_amount
created_at
updated_at
```

### order_items

```text
id
order_id
sku
quantity
unit_price
```

`unit_price` is populated from a static demo price map seeded in Order Service (see Seed Data below) — not looked up synchronously from Inventory Service, and not a column on `inventory_items`. See `docs/db-ownership.md` §4 ("Where prices come from") for the frozen decision and its cost (product data split across two owners).

### order_status_history

```text
id
order_id
status
source_event_id
occurred_at
```

---

## Inventory Service

### inventory_items

```text
sku
display_name
available_quantity
reserved_quantity
version
updated_at
```

### inventory_reservations

```text
id
order_id
sku
quantity
status
created_at
updated_at
```

Use database locking or optimistic concurrency controls to ensure limited stock cannot be oversold during contention scenarios.

---

## Payment Service

### payment_attempts

```text
id
order_id
idempotency_key
status
amount
failure_reason
created_at
updated_at
```

---

## Fulfillment Service

### shipments

```text
id
order_id
status
tracking_number
created_at
updated_at
```

---

## Scenario Service

Not in the original data model — added by Phase 0 because `GET /demo/scenario-runs/{runId}` (this doc) and the Scenario Run Detail page (`frontend-design.md`) both require a stored run with a timeline, and nothing here defined one. See `docs/db-ownership.md` §1/§3 for the frozen schema.

### scenario_runs

```text
id
scenario_name
status
correlation_id
order_id
started_at
completed_at
error_message
```

### scenario_run_timeline

```text
id
run_id
sequence
label
kind
occurred_at
detail
```

`detail` is nullable JSON — populated with partition/offset/eventId/etc. when the runtime actually knows them, left absent otherwise, per this doc's own "do not fabricate these fields" rule in the Scenario Run Detail page spec.

---

## Reliability tables (per-service, not shared)

```text
processed_events
outbox_events
```

Each service that needs these gets its **own** copy in its own schema — a single cross-service table would put every consumer in one write hotspot and break the transactional guarantee that makes the idempotency check work (the dedup insert must commit in the same local transaction as the business change it guards). "Shared" describes the *pattern* repeated across services, not a table shared *by* them. (Retitled from "Shared reliability tables where needed" — the original heading read as though one table served all services, contradicted by this section's own next sentence; see `docs/agent-reports/phase-0.md` §4.6 and `docs/db-ownership.md` §2.)

---

# 9a. Suggested Order States

Keep the state machine small.

Possible states:

```text
PENDING
INVENTORY_RESERVED
REJECTED_OUT_OF_STOCK
PAYMENT_PENDING
PAYMENT_FAILED
PAID
FULFILLMENT_PENDING
FULFILLED
FAILED
```

(Corrected from `OUT_OF_STOCK` — this draft and the failed-inventory flow diagram below disagreed on the name for the same state; `REJECTED_OUT_OF_STOCK` is what `frontend-design.md`'s Scenario 2 and the frozen `docs/order-state-machine.md` both use. Flagged in `docs/agent-reports/phase-0.md` §4.1.)

Avoid creating dozens of states.

Define valid transitions explicitly.

Example:

```text
PENDING
  -> INVENTORY_RESERVED
  -> REJECTED_OUT_OF_STOCK

INVENTORY_RESERVED
  -> PAYMENT_PENDING

PAYMENT_PENDING
  -> PAID
  -> PAYMENT_FAILED

PAID
  -> FULFILLMENT_PENDING

FULFILLMENT_PENDING
  -> FULFILLED
```

This is the draft Phase 0 should formalize into `docs/order-state-machine.md` (see execution-plan.md §4, Phase 0 outputs) — treat it as the starting point, not a from-scratch design task.

---

# 9b. Seed Data

Keep products intentionally small.

Example:

```text
SKU-001  Mechanical Keyboard
SKU-002  USB-C Dock
SKU-003  Developer Mug
SKU-004  External SSD
```

The product catalog is not the project.

Seed stock values should make scenarios easy to reproduce.

Example:

```text
SKU-001: 10
SKU-002: 5
SKU-003: 100
SKU-004: 2
```

Note why these specific quantities: SKU-004's stock of 2 is deliberately scarce so Scenario 7 (Inventory Contention) is trivial to trigger with two concurrent small orders; SKU-003's stock of 100 gives headroom for Scenario 8 (High-Volume Batch) without artificial restocking.

This draft originally specified no prices anywhere — a real gap, since `orders.total_amount`, `order_items.unit_price`, and `PaymentRequested.amount` all need one. Phase 0 froze a static price map, seeded in Order Service only (`inventory_items` still holds no price column):

```text
SKU-001  Mechanical Keyboard   $129.00
SKU-002  USB-C Dock            $189.00
SKU-003  Developer Mug         $14.50
SKU-004  External SSD          $249.00
```

See `docs/db-ownership.md` §4 for why this lives in Order Service rather than Inventory, and the acknowledged cost (product data split across two service owners).

---

# 10. REST API Design

The REST API should remain conventional and production-like.

## Order endpoints

```http
POST /api/orders
GET /api/orders
GET /api/orders/{orderId}
```

Example create request:

```json
{
  "customerId": "demo-customer",
  "items": [
    {
      "sku": "SKU-001",
      "quantity": 2
    }
  ]
}
```

Example immediate response:

```json
{
  "id": "order-21873",
  "status": "PENDING",
  "createdAt": "2026-08-07T20:31:04.220Z"
}
```

The REST request should **not wait for the entire Kafka workflow**.

That distinction is important:

- HTTP confirms the order has been accepted,
- Kafka handles later fulfillment work asynchronously,
- the frontend observes status changes afterward.

---

## Scenario endpoints

Keep these under a clearly separate namespace:

```http
POST /demo/scenarios/{scenarioName}
GET /demo/scenario-runs/{runId}
POST /demo/reset
```

Do not intermingle them with `/api/orders`.

---
