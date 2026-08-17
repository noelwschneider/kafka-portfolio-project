# 11. Frontend Product Direction

The React/TypeScript application should be an **engineering demonstration console**.

Stack: Vite + React + TypeScript. Use TanStack Query for REST data fetching/caching and native `EventSource` for SSE (see project-overview.md's Pinned Technology Decisions table, and the Live Frontend Updates section below). Do not add Redux or another global state library — server state belongs in TanStack Query's cache, and the amount of local UI state in this app does not justify a separate state management library.

Recommended navigation:

```text
Overview
Orders
Scenarios
Event Explorer
System Health
Architecture
```

---

# 12. Frontend Pages

## 12.1 Overview

Purpose:

- immediately explain what the project is,
- show system health,
- show recent orders/scenarios,
- expose the most important demo actions.

Suggested elements:

```text
ORDER FULFILLMENT SYSTEMS LAB
Event-driven Spring Boot / Kafka demonstration

System Status
--------------------------------
Order Service        Healthy
Inventory Service    Healthy
Payment Service      Healthy
Fulfillment Service  Healthy
Kafka                Healthy
PostgreSQL           Healthy

Quick Scenarios
--------------------------------
[ Standard Fulfillment ]
[ Inventory Outage ]
[ Duplicate Event ]
[ Payment Rejection ]
```

Include a one-paragraph summary of the architecture.

---

## 12.2 Orders

Provide a minimal production-style workflow.

Functions:

- create order,
- select from a tiny fixed product list,
- set quantity,
- submit,
- list recent orders,
- open order details.

Avoid building:

- shopping carts,
- wishlists,
- real checkout,
- elaborate product pages,
- discount engines,
- customer account systems.

---

## 12.3 Scenarios

This should be the centerpiece.

Each scenario card should contain:

- scenario name,
- short explanation,
- what it demonstrates,
- expected behavior,
- run button.

Example:

### Duplicate Event Delivery

**Behavior**  
Publishes the same logical order event twice.

**Demonstrates**

- at-least-once delivery assumptions,
- event IDs,
- idempotent consumers,
- duplicate suppression.

Button:

```text
[ Run Scenario ]
```

---

## 12.4 Scenario Run Detail

This screen should visualize actual activity.

Example:

```text
Scenario Run #193
Status: COMPLETED
Elapsed: 1.42s

Order: order-21873

Timeline
------------------------------------------------
12:31:04.120  POST /api/orders       201
12:31:04.184  OrderCreated           published
12:31:04.219  OrderCreated           consumed
12:31:04.251  InventoryReserved      published
12:31:04.292  PaymentRequested       published
12:31:04.361  PaymentAuthorized      published
12:31:04.418  ShipmentCreated        published
12:31:04.447  Order                  FULFILLED
```

For each event, allow expandable metadata:

```text
topic
partition
offset
eventId
correlationId
aggregateId
producer
consumer
processing duration
retry count
```

Do not fabricate these fields. Display only values actually available from the system.

---

## 12.5 Event Explorer

Provide a view of recent events.

Filters:

- event type,
- order ID,
- correlation ID,
- service,
- topic,
- status,
- dead-lettered/not dead-lettered.

This may be backed by a lightweight event projection/audit store rather than attempting to query Kafka as if it were a database.

---

## 12.6 System Health

Show:

- service health,
- service version/build,
- Kafka connectivity,
- database connectivity,
- consumer status if available,
- recent errors.

Potential later enhancements:

- consumer lag,
- request rate,
- processing latency,
- pod replica count.

---

## 12.7 Architecture

Provide:

- architecture diagram,
- service responsibilities,
- event flow,
- explanation of why Kafka is used,
- explanation of why Kubernetes is used,
- brief reliability notes,
- link to repository documentation.

This makes the project's engineering decisions discoverable even if a reviewer only spends a minute with it.

---

# 13. Live Frontend Updates

Prefer **Server-Sent Events (SSE)** for the first implementation unless bidirectional communication is genuinely needed.

Use SSE to push:

- order status transitions,
- scenario progress,
- event timeline entries,
- health changes where useful.

Why SSE is a good fit:

- browser-native,
- simpler than WebSockets,
- server-to-client updates are sufficient for this product.

WebSockets are acceptable if chosen deliberately, but should not be added simply for resume keyword value.

---

# 14. Required Demo Scenarios

Implement these incrementally.

## Scenario 1: Standard Fulfillment

### Behavior
Create an order with available inventory and successful payment.

### Demonstrates
- REST request,
- persistence,
- event publication,
- Kafka consumption,
- asynchronous workflow,
- state transitions.

### Expected terminal state
`FULFILLED`

---

## Scenario 2: Out of Stock

### Behavior
Create an order requesting more inventory than exists.

### Demonstrates
- domain validation,
- inventory ownership,
- rejection events,
- asynchronous failure propagation.

### Expected terminal state
`REJECTED_OUT_OF_STOCK`

---

## Scenario 3: Payment Rejection

### Behavior
Inventory reserves successfully; payment simulator rejects authorization.

### Demonstrates
- downstream business failure,
- compensation,
- inventory release,
- eventual state correction.

### Expected terminal state
`PAYMENT_FAILED`

---

## Scenario 4: Duplicate Event Delivery

### Behavior
Deliver the same logical event twice.

### Demonstrates
- at-least-once processing assumptions,
- event IDs,
- idempotent consumers,
- duplicate detection.

### Success condition
The duplicate produces no duplicate reservation/payment/shipment side effects.

---

## Scenario 5: Consumer Outage and Recovery

### Behavior
Temporarily stop or disable a consumer, publish work, then restore processing.

### Demonstrates
- Kafka durability,
- offsets,
- asynchronous decoupling,
- consumer recovery.

### Success condition
The pending event is processed after the consumer becomes available again.

---

## Scenario 6: Poison Message / DLQ

### Behavior
Publish an event that repeatedly fails processing.

### Demonstrates
- retry policy,
- backoff,
- bounded failure,
- dead-letter routing,
- operational troubleshooting.

### Success condition
The event ends in the expected DLQ and is visible in the UI.

---

## Scenario 7: Inventory Contention

### Behavior
Create several simultaneous orders for stock that cannot satisfy all requests.

Example:

```text
Available stock = 2 (SKU-004, seeded deliberately scarce — see backend-design.md's Seed Data section)

Order A requests 2
Order B requests 2
```

(Corrected from an SKU-002/stock-of-5 example that disagreed with `backend-design.md`'s seed-data rationale for the same scenario. Frozen on SKU-004 in `docs/scenarios.md` — see `docs/agent-reports/phase-0.md` §4.5.)

### Demonstrates
- concurrent access,
- transaction isolation,
- locking/versioning,
- consistency under contention.

### Success condition
Total reserved inventory never exceeds available inventory.

---

## Scenario 8: High-Volume Batch

### Behavior
Generate many orders quickly.

### Demonstrates
- event throughput,
- consumer groups,
- horizontal scaling,
- lag/processing behavior,
- Kubernetes scaling if HPA is configured.

This should be a later-stage scenario.

---

# 28. Frontend UX Principle

The frontend should answer this question:

> "What engineering behavior can I see in the next sixty seconds?"

Every major page should make important system behavior obvious.

Prefer:

- timelines,
- state diagrams,
- event metadata,
- scenario controls,
- health indicators,
- failure messages,
- retry indicators.

Avoid:

- generic marketing copy,
- elaborate ecommerce imagery,
- fake testimonials,
- excessive animations.

The interface should look like a polished developer/operations tool.

---
