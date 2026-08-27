import { MermaidDiagram } from '../components/MermaidDiagram';

// frontend-design.md §12.7. This page keeps only the happy-path sequence diagram as an
// at-a-glance illustration and links out to the repo's own architecture docs for full detail,
// rather than duplicating them here — a duplicated system-overview diagram on this page drifted
// badly out of sync with docs/architecture-diagram.md behind Phases 7-10 and ADR-009.
const REPO_BASE = 'https://github.com/noelwschneider/kafka-portfolio-project/blob/main';

const HAPPY_PATH_DIAGRAM = `sequenceDiagram
    autonumber
    actor Client
    participant ORD as Order Service<br/>(orders, status history)
    participant K as Kafka
    participant INV as Inventory Service<br/>(stock, reservations)
    participant PAY as Payment Service<br/>(payment attempts)
    participant FUL as Fulfillment Service<br/>(shipments)

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
      <p>
        Five services — Order, Inventory, Payment, Fulfillment, and the Scenario demo control
        plane — talk to each other only through Kafka, never directly. Each owns exactly one
        PostgreSQL schema, with no shared tables and no cross-schema reads.
      </p>

      <h2>Happy path — order reaches FULFILLED</h2>
      <MermaidDiagram source={HAPPY_PATH_DIAGRAM} />
      <p className="hint">
        The HTTP response returns before any downstream event fires — the visible proof that
        <code> POST /api/orders</code> does not wait for fulfillment.
      </p>

      <h2>Technology</h2>
      <table>
        <thead>
          <tr>
            <th>Concern</th>
            <th>Technology</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>Backend language / framework</td>
            <td>Java 21 (LTS), Spring Boot</td>
          </tr>
          <tr>
            <td>Backend build tool</td>
            <td>Maven (multi-module)</td>
          </tr>
          <tr>
            <td>Messaging</td>
            <td>Apache Kafka (native KRaft, no ZooKeeper)</td>
          </tr>
          <tr>
            <td>Database</td>
            <td>PostgreSQL, one schema per service</td>
          </tr>
          <tr>
            <td>Schema migrations</td>
            <td>Flyway</td>
          </tr>
          <tr>
            <td>Orchestration</td>
            <td>Kubernetes (plain YAML manifests, no Helm)</td>
          </tr>
          <tr>
            <td>Frontend build tool</td>
            <td>Vite</td>
          </tr>
          <tr>
            <td>Frontend framework</td>
            <td>React, TypeScript</td>
          </tr>
          <tr>
            <td>Frontend data fetching</td>
            <td>TanStack Query for REST; native EventSource for SSE</td>
          </tr>
          <tr>
            <td>CI</td>
            <td>GitHub Actions, path-filtered per service</td>
          </tr>
        </tbody>
      </table>

      <h2>Repository documentation</h2>
      <ul>
        <li>
          <a href={`${REPO_BASE}/docs/architecture-diagram.md`} target="_blank" rel="noreferrer">docs/architecture-diagram.md</a>{' '}
          — the system-overview diagram, plus failure-path sequence diagrams
        </li>
        <li>
          <a href={`${REPO_BASE}/docs/scenarios.md`} target="_blank" rel="noreferrer">docs/scenarios.md</a>{' '}
          — the eight demo scenarios in full
        </li>
        <li>
          <a href={`${REPO_BASE}/docs/events/event-catalog.md`} target="_blank" rel="noreferrer">docs/events/event-catalog.md</a>{' '}
          — event envelope, topics, publisher/consumer map
        </li>
        <li>
          <a href={`${REPO_BASE}/docs/order-state-machine.md`} target="_blank" rel="noreferrer">docs/order-state-machine.md</a>{' '}
          — order status transitions
        </li>
        <li>
          <a href={`${REPO_BASE}/docs/db-ownership.md`} target="_blank" rel="noreferrer">docs/db-ownership.md</a>{' '}
          — per-service schema ownership
        </li>
        <li>
          <a href={`${REPO_BASE}/docs/adr`} target="_blank" rel="noreferrer">docs/adr/</a>{' '}
          — architecture decision records (ADR-001 through ADR-011)
        </li>
        <li>
          <a href={`${REPO_BASE}/docs/reliability-pattern.md`} target="_blank" rel="noreferrer">docs/reliability-pattern.md</a>{' '}
          — idempotency/retry/DLQ implementation detail
        </li>
      </ul>
    </section>
  );
}
