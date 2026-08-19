# Architecture Diagram

Diagrams render natively on GitHub (Mermaid). Sources of truth for the details behind them:
`docs/events/event-catalog.md` (events, topics, publishers/consumers),
`docs/order-state-machine.md` (statuses), `docs/db-ownership.md` (tables),
`docs/scenarios.md` (demo scenarios).

Everything drawn here is the **Phase 3+ target**: four business services plus a Scenario Service,
each with its own schema, communicating through Kafka. Phase 1 is the same domain logic as in-process
modules with no Kafka, and Phase 2 adds Kafka while still deploying as one application
(`docs/planning/implementation-phases.md`).

---

## 1. System overview

```mermaid
flowchart TB
    UI["React / TypeScript console<br/>Vite + TanStack Query + EventSource"]

    subgraph services["Backend services (Spring Boot, Java 21)"]
        ORD["Order Service<br/>owns order lifecycle status"]
        INV["Inventory Service<br/>owns stock, reservations"]
        PAY["Payment Service<br/>simulator, no real provider"]
        FUL["Fulfillment Service<br/>owns shipments"]
        SCN["Scenario Service<br/>demo control plane only"]
    end

    subgraph kafka["Apache Kafka (KRaft)"]
        T_ORD["orders.events"]
        T_INV["inventory.events"]
        T_PAY["payments.events"]
        T_FUL["fulfillment.events"]
        T_DLQ["orders.dlq / inventory.dlq<br/>payments.dlq / fulfillment.dlq"]
    end

    subgraph pg["PostgreSQL — one schema per service"]
        S_ORD["order_service<br/>orders, order_items,<br/>order_status_history,<br/>outbox_events, processed_events"]
        S_INV["inventory_service<br/>inventory_items,<br/>inventory_reservations,<br/>processed_events"]
        S_PAY["payment_service<br/>payment_attempts,<br/>processed_events"]
        S_FUL["fulfillment_service<br/>shipments,<br/>processed_events"]
        S_SCN["scenario_service<br/>scenario_runs,<br/>scenario_run_timeline"]
    end

    UI -->|"REST /api"| ORD
    UI -->|"REST /api"| INV
    UI -->|"REST /demo"| SCN
    UI -->|"SSE order-status-changed"| ORD
    UI -->|"SSE timeline-entry"| SCN

    SCN -->|"POST /api/orders"| ORD
    SCN -->|"/demo payment behavior"| PAY
    SCN -->|"/demo pause + resume"| INV
    SCN -->|"/demo pause + resume"| FUL

    ORD -->|"publish OrderCreated,<br/>PaymentRequested"| T_ORD
    T_ORD -->|"consume"| INV
    T_ORD -->|"consume"| PAY

    INV -->|"publish InventoryReserved,<br/>InventoryReservationFailed,<br/>InventoryReleased"| T_INV
    T_INV -->|"consume"| ORD

    PAY -->|"publish PaymentAuthorized,<br/>PaymentRejected"| T_PAY
    T_PAY -->|"consume"| ORD
    T_PAY -->|"consume"| FUL
    T_PAY -->|"consume PaymentRejected<br/>to release stock"| INV

    FUL -->|"publish ShipmentCreated"| T_FUL
    T_FUL -->|"consume"| ORD

    SCN -->|"duplicate + poison records"| T_ORD
    INV -.->|"retries exhausted"| T_DLQ
    PAY -.->|"retries exhausted"| T_DLQ
    FUL -.->|"retries exhausted"| T_DLQ

    ORD --- S_ORD
    INV --- S_INV
    PAY --- S_PAY
    FUL --- S_FUL
    SCN --- S_SCN
```

Three things the diagram is meant to make obvious:

- **No arrow between two business services.** Order, Inventory, Payment, and Fulfillment communicate
  only through Kafka. The only synchronous service-to-service arrows come from Scenario Service, and
  they are demo control, not workflow (ADR-002).
- **Each service touches exactly one schema.** No shared tables, no cross-schema reads (ADR-004).
- **A service publishes only to its own topic.** `PaymentRequested` sits on `orders.events` because
  Order Service publishes it (`docs/events/event-catalog.md` §2).

---

## 2. Happy path — order reaches `FULFILLED`

Scenario 1 (`POST /demo/scenarios/standard-order`). The HTTP response returns long before the
workflow finishes, which is the point.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant ORD as Order Service
    participant K as Kafka
    participant INV as Inventory Service
    participant PAY as Payment Service
    participant FUL as Fulfillment Service

    Client->>ORD: POST /api/orders
    ORD->>ORD: persist order, status PENDING
    ORD->>K: publish OrderCreated (orders.events, key=orderId)
    ORD-->>Client: 201 {id, status PENDING, createdAt}
    Note over Client,ORD: Request returns here. Everything below is asynchronous.

    K->>INV: OrderCreated
    INV->>INV: reserve stock under optimistic locking
    INV->>K: publish InventoryReserved (inventory.events)

    K->>ORD: InventoryReserved
    ORD->>ORD: status INVENTORY_RESERVED, then PAYMENT_PENDING
    ORD->>K: publish PaymentRequested (orders.events)

    K->>PAY: PaymentRequested
    PAY->>PAY: record attempt, authorize simulated payment
    PAY->>K: publish PaymentAuthorized (payments.events)

    K->>ORD: PaymentAuthorized
    ORD->>ORD: status PAID, then FULFILLMENT_PENDING
    K->>FUL: PaymentAuthorized
    Note over ORD,FUL: One event, two independent consumer groups. Neither waits for the other.

    FUL->>FUL: create shipment, generate tracking number
    FUL->>K: publish ShipmentCreated (fulfillment.events)

    K->>ORD: ShipmentCreated
    ORD->>ORD: status FULFILLED
    ORD-->>Client: SSE order-status-changed FULFILLED
```

---

## 3. Inventory failure path — order reaches `REJECTED_OUT_OF_STOCK`

Scenario 2 (`POST /demo/scenarios/out-of-stock`). A business rejection: nothing is retried, nothing is
dead-lettered, and there is nothing to compensate because no stock was ever held.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant ORD as Order Service
    participant K as Kafka
    participant INV as Inventory Service

    Client->>ORD: POST /api/orders (5 x SKU-004, stock 2)
    ORD->>ORD: persist order, status PENDING
    ORD->>K: publish OrderCreated
    ORD-->>Client: 201 {status PENDING}
    Note over Client,ORD: The order is accepted over HTTP. Rejection arrives asynchronously.

    K->>INV: OrderCreated
    INV->>INV: insufficient stock — reserve nothing
    INV->>K: publish InventoryReservationFailed (reason INSUFFICIENT_STOCK, shortages[])

    K->>ORD: InventoryReservationFailed
    ORD->>ORD: status REJECTED_OUT_OF_STOCK (terminal)
    ORD-->>Client: SSE order-status-changed REJECTED_OUT_OF_STOCK
```

Reservation is all-or-nothing per order: a partially satisfiable order fails whole rather than
reserving a subset of its lines.

---

## 4. Payment failure path — order reaches `PAYMENT_FAILED`, stock released

Scenario 3 (`POST /demo/scenarios/payment-failure`). This is the compensation path — the closest thing
to a saga in the project, without an orchestration framework.

```mermaid
sequenceDiagram
    autonumber
    participant SCN as Scenario Service
    participant ORD as Order Service
    participant K as Kafka
    participant INV as Inventory Service
    participant PAY as Payment Service

    SCN->>PAY: PUT /demo/payment-behavior {mode REJECT}
    SCN->>ORD: POST /api/orders
    ORD->>K: publish OrderCreated
    ORD-->>SCN: 201 {status PENDING}

    K->>INV: OrderCreated
    INV->>INV: reserve stock — succeeds
    INV->>K: publish InventoryReserved

    K->>ORD: InventoryReserved
    ORD->>ORD: status INVENTORY_RESERVED, then PAYMENT_PENDING
    ORD->>K: publish PaymentRequested

    K->>PAY: PaymentRequested
    PAY->>PAY: simulator declines — record REJECTED
    PAY->>K: publish PaymentRejected (failureReason CARD_DECLINED)

    par Order Service updates lifecycle
        K->>ORD: PaymentRejected
        ORD->>ORD: status PAYMENT_FAILED (terminal)
    and Inventory Service compensates
        K->>INV: PaymentRejected
        INV->>INV: release reservation, restore available stock
        INV->>K: publish InventoryReleased
    end

    Note over ORD,INV: Compensation is a second consumer of the same event —<br/>no orchestrator, no distributed transaction.
    SCN->>PAY: DELETE /demo/payment-behavior
```

A **retryable** simulated provider error is a different path and must not be confused with this one:
it raises inside the Payment Service consumer, is retried with backoff, and lands in `payments.dlq` if
attempts are exhausted — leaving the order in `PAYMENT_PENDING` rather than `PAYMENT_FAILED`
(`docs/events/event-catalog.md`, `PaymentRejected`).

---

## 5. Delivery and consistency properties

Stated plainly so no diagram above is read as promising more than the implementation provides
(`docs/planning/agent-guidance.md` rule 18):

- **At-least-once delivery.** Consumers may see any record more than once and are made idempotent
  through a per-service `processed_events` ledger (ADR-005). This project does **not** implement
  exactly-once semantics.
- **Per-partition ordering only.** `orderId` is the record key, so one order's events keep their
  relative order. Nothing guarantees ordering across orders.
- **Eventual consistency across services.** An order can be `PAID` for a few milliseconds before a
  shipment exists. Every read is a snapshot of one service's view.
- **A dual-write window still exists in three of the four services.** Inventory, Payment and
  Fulfillment Service persist and then publish, so a crash in between loses the event. Order Service
  closed this in Phase 6 (ADR-006): both events it produces — `OrderCreated` and `PaymentRequested` —
  are written to an `outbox_events` row in the same transaction as the business change, and a
  background publisher sends them and marks them published. That makes Order Service's publication
  durable, not exactly-once: a crash between the send and the mark resends the row, which the
  idempotent consumers above absorb.
