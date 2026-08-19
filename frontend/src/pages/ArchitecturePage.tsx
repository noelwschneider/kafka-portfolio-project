import { MermaidDiagram } from '../components/MermaidDiagram';

// frontend-design.md §12.7. Diagram source and prose below are transcribed from
// docs/architecture-diagram.md (read-only per this phase's rules — flag, don't edit) so a reviewer
// gets the same picture here as in the repo's own docs, rendered rather than left as a link.
const SYSTEM_OVERVIEW_DIAGRAM = `flowchart TB
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
        S_ORD["order_service"]
        S_INV["inventory_service"]
        S_PAY["payment_service"]
        S_FUL["fulfillment_service"]
        S_SCN["scenario_service"]
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
`;

const HAPPY_PATH_DIAGRAM = `sequenceDiagram
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
`;

export function ArchitecturePage() {
  return (
    <section className="architecture-page">
      <div className="page-header">
        <h1>Architecture</h1>
      </div>

      <h2>System overview</h2>
      <MermaidDiagram source={SYSTEM_OVERVIEW_DIAGRAM} />
      <ul>
        <li>No arrow between two business services — Order, Inventory, Payment, and Fulfillment communicate only through Kafka (ADR-002).</li>
        <li>Each service touches exactly one PostgreSQL schema. No shared tables, no cross-schema reads (ADR-004).</li>
        <li>A service publishes only to its own topic — e.g. <code>PaymentRequested</code> sits on <code>orders.events</code> because Order Service publishes it.</li>
      </ul>

      <h2>Happy path — order reaches FULFILLED</h2>
      <MermaidDiagram source={HAPPY_PATH_DIAGRAM} />
      <p className="hint">
        The HTTP response returns before any downstream event fires — the visible proof that
        <code> POST /api/orders</code> does not wait for fulfillment.
      </p>

      <h2>Service responsibilities</h2>
      <table>
        <thead>
          <tr>
            <th>Service</th>
            <th>Owns</th>
            <th>Port</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>Order Service</td>
            <td>Order lifecycle status, order items, status history</td>
            <td>8081</td>
          </tr>
          <tr>
            <td>Inventory Service</td>
            <td>Stock levels, reservations</td>
            <td>8082</td>
          </tr>
          <tr>
            <td>Payment Service</td>
            <td>Payment attempts (simulator — no real payment provider)</td>
            <td>8083</td>
          </tr>
          <tr>
            <td>Fulfillment Service</td>
            <td>Shipments</td>
            <td>8084</td>
          </tr>
          <tr>
            <td>Scenario Service</td>
            <td>Demo control plane — runs scenarios, records their timelines. No business data.</td>
            <td>8085</td>
          </tr>
        </tbody>
      </table>

      <h2>Event flow</h2>
      <table>
        <thead>
          <tr>
            <th>Topic</th>
            <th>Published by</th>
            <th>Carries</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>orders.events</td>
            <td>Order Service</td>
            <td>OrderCreated, PaymentRequested</td>
          </tr>
          <tr>
            <td>inventory.events</td>
            <td>Inventory Service</td>
            <td>InventoryReserved, InventoryReservationFailed, InventoryReleased</td>
          </tr>
          <tr>
            <td>payments.events</td>
            <td>Payment Service</td>
            <td>PaymentAuthorized, PaymentRejected</td>
          </tr>
          <tr>
            <td>fulfillment.events</td>
            <td>Fulfillment Service</td>
            <td>ShipmentCreated</td>
          </tr>
          <tr>
            <td>*.dlq (four topics)</td>
            <td>the failing consumer</td>
            <td>Records that exhausted retries, plus failure metadata</td>
          </tr>
        </tbody>
      </table>

      <h2>Why Kafka</h2>
      <p>
        ADR-001: business services need to react to each other's outcomes without becoming
        synchronously coupled — an inventory reservation, a payment authorization, and a shipment
        creation are independent units of work that should not block on each other's availability.
        Kafka gives durable, replayable, ordered-per-key delivery, which is what backs Scenario 5
        (consumer outage/recovery — the backlog survives a paused consumer) and Scenario 6 (poison
        message routes to a DLQ instead of blocking the partition).
      </p>

      <h2>Why Kubernetes</h2>
      <p>
        <strong>Not yet built.</strong> Per ADR-007, Kubernetes is introduced only once the local
        service boundaries (four services + Scenario Service, Phase 3) have stabilized —
        Kubernetes is a deployment/scaling concern layered on top of an already-correct system, not
        a prerequisite for developing it (<code>docs/planning/agent-guidance.md</code> rule 19:
        "Do not make Kubernetes a prerequisite for early local development"). The plan for when it
        does land (Phase 7+, per <code>docs/planning/implementation-phases.md</code>): one
        Deployment per service, readiness/liveness probes backed by each service's Actuator health
        endpoints, and an HPA on Order/Inventory Service to make Scenario 8 (High-Volume Batch)
        demonstrate real horizontal scaling under load — none of that exists in the running system
        today.
      </p>

      <h2>Reliability notes</h2>
      <p>
        Delivery is <strong>at-least-once</strong>, never exactly-once — this project makes no
        stronger claim (<code>docs/planning/agent-guidance.md</code> rule 18,{' '}
        <code>docs/reliability-pattern.md</code>). Consumers are made idempotent through a
        per-service <code>processed_events</code> ledger keyed on <code>(event_id, consumer_name)</code>,
        with the ledger row and the business change committed in the same local transaction — this
        is what makes Scenario 4's duplicate delivery produce no duplicate side effect. Retries use
        bounded backoff; a record that exhausts retries is routed to that consumer's DLQ with
        failure metadata rather than blocking its partition indefinitely (Scenario 6).
      </p>
      <p>
        <strong>Durable publication, in one service.</strong> Order Service no longer publishes to
        Kafka after committing. Both events it produces — <code>OrderCreated</code> and{' '}
        <code>PaymentRequested</code> — are written to an <code>outbox_events</code> row in the same
        transaction as the business change, and a background publisher polls those rows, sends them
        in insertion order and marks them published (Phase 6, ADR-006). A crash can no longer strand
        an order with an event that was never published; it can only resend one that was, which the
        idempotent consumers above already absorb. This is still at-least-once, not exactly-once.
      </p>
      <p>
        <strong>The gap that remains.</strong> Inventory, Payment and Fulfillment Service still
        publish after committing, so a crash in that window loses the event. It does not heal
        itself: those publishes happen after a transaction that already claimed the{' '}
        <code>processed_events</code> row for the event being handled, so a redelivery is skipped as
        a duplicate rather than republishing. Closing it for those three is not done, and the
        architecture claims above apply to Order Service only.
      </p>

      <h2>Repository documentation</h2>
      <ul>
        <li><code>docs/architecture-diagram.md</code> — the diagrams above, plus failure-path sequence diagrams</li>
        <li><code>docs/scenarios.md</code> — the eight demo scenarios in full</li>
        <li><code>docs/events/event-catalog.md</code> — event envelope, topics, publisher/consumer map</li>
        <li><code>docs/order-state-machine.md</code> — order status transitions</li>
        <li><code>docs/db-ownership.md</code> — per-service schema ownership</li>
        <li><code>docs/adr/</code> — architecture decision records (ADR-001 through ADR-007)</li>
        <li><code>docs/reliability-pattern.md</code> — idempotency/retry/DLQ implementation detail</li>
      </ul>
    </section>
  );
}
