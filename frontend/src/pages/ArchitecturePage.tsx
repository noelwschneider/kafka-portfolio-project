import { MermaidDiagram } from '../components/MermaidDiagram';

// frontend-design.md §12.7. This page keeps only the happy-path sequence diagram as an
// at-a-glance illustration and links out to the repo's own architecture docs for full detail,
// rather than duplicating them here — a duplicated system-overview diagram on this page drifted
// badly out of sync with docs/architecture-diagram.md behind Phases 7-10 and ADR-009.
const REPO_BASE = 'https://github.com/noelwschneider/kafka-portfolio-project/blob/main';

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
      <p>
        Five services — Order, Inventory, Payment, Fulfillment, and the Scenario demo control
        plane — talk to each other only through Kafka, never directly. Each owns exactly one
        PostgreSQL schema, with no shared tables and no cross-schema reads. The full diagram, with
        every topic, schema, and REST/SSE edge, lives in the repo:
      </p>
      <ul>
        <li>
          <a href={`${REPO_BASE}/docs/architecture-diagram.md`} target="_blank" rel="noreferrer">
            docs/architecture-diagram.md
          </a>{' '}
          — the full system diagram plus failure-path sequence diagrams
        </li>
        <li>
          <a href={`${REPO_BASE}/docs/adr/ADR-002-separate-demo-and-business-apis.md`} target="_blank" rel="noreferrer">
            ADR-002
          </a>{' '}
          — why business services never call each other directly, only through Kafka
        </li>
        <li>
          <a href={`${REPO_BASE}/docs/adr/ADR-004-postgresql-per-service-ownership-boundaries.md`} target="_blank" rel="noreferrer">
            ADR-004
          </a>{' '}
          — why each service owns exactly one PostgreSQL schema
        </li>
        <li>
          <a href={`${REPO_BASE}/docs/adr`} target="_blank" rel="noreferrer">
            docs/adr/
          </a>{' '}
          — all architecture decision records
        </li>
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
        Per ADR-007, Kubernetes was introduced only once the local service boundaries (four
        services + Scenario Service, Phase 3) had stabilized — it's a deployment/scaling concern
        layered on top of an already-correct system, not a prerequisite for developing it
        (<code>docs/planning/agent-guidance.md</code> rule 19: "Do not make Kubernetes a
        prerequisite for early local development"). It landed in Phase 8: one Deployment and
        Service per backend service and the frontend, ConfigMaps and Secrets for configuration,
        and readiness/liveness probes backed by each service's Actuator health endpoints, all as
        plain YAML manifests (no Helm) targeting local <code>kind</code>.
      </p>
      <p>
        <strong>Scaling is manual, not automatic.</strong> No <code>HorizontalPodAutoscaler</code> is
        configured — Phase 10's high-volume scenario (Scenario 8) demonstrates the underlying
        mechanism with <code>kubectl scale deployment/inventory-service --replicas=N</code> instead.
        Real measurements exist at 1 and 2 Inventory Service replicas (submission throughput and
        Kafka consumer-lag both observed to change with replica count, on the live cluster, not
        simulated). A 3rd replica was attempted repeatedly but not obtained on this project's own
        development hardware — a local Docker Desktop VM resource ceiling under concurrent JVM pod
        load, not a defect in the manifests or the scaling mechanism. The topic's 3-partition
        ceiling means a working replica=3 run is architecturally expected to help, and a 4th
        replica is expected to help no further — stated as the documented expectation, not a
        measured result.
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
        outbox claims above apply to Order Service only.
      </p>
      <p>
        <strong>Order Service's own status is guarded against cross-topic reordering (ADR-009).</strong>{' '}
        <code>PaymentAuthorized</code> and <code>ShipmentCreated</code> are consumed by Order Service
        on independent listeners with no ordering guarantee between the two topics, and under load
        the later-in-the-workflow event can be processed first — a real defect found live during
        Phase 10's load testing, where it corrupted order state under concurrency. Every write is
        now classified against <code>docs/order-state-machine.md</code>'s frozen transition table
        before being applied: an early transition is parked in a <code>deferred_transitions</code> table
        and applied once its predecessor lands, and a transition the order already passed is
        dropped. <code>orders.status</code> only ever holds a status reachable by a valid transition
        sequence and never reverts out of a terminal state — reproduced and verified by a
        deterministic integration test, not just documentation intent.
      </p>

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
