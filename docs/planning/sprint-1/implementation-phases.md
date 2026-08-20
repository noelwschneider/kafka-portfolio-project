# 25. Implementation Phases

## Phase 0 — Design Contract

Before implementation, define:

- service boundaries,
- order states,
- event names,
- event envelope,
- core database tables,
- scenario list,
- initial APIs.

Deliverables:

- this plan,
- architecture diagram,
- initial OpenAPI definitions,
- event catalog.

Do not begin with Kubernetes.

---

## Phase 1 — Modular Monolith / Core Domain

Goal: prove the business workflow before distributing it.

Build one Spring Boot application with modules for:

- orders,
- inventory,
- payments,
- fulfillment.

Use:

- PostgreSQL,
- REST,
- JPA/Hibernate,
- Flyway,
- validation,
- centralized error handling,
- unit/integration tests.

Build a minimal React UI for:

- create order,
- list orders,
- order detail.

No Kafka required yet.

### Exit criteria

- happy-path order works,
- out-of-stock behavior works,
- payment rejection works,
- tests protect domain rules.

---

## Phase 2 — Introduce Kafka

Replace direct in-process workflow transitions with events.

Implement:

- event envelope,
- producer configuration,
- consumer configuration,
- topics,
- keyed messages,
- correlation IDs.

Keep services in one codebase/module structure initially if that reduces complexity.

### Exit criteria

- happy path fully travels through Kafka,
- order REST endpoint returns before fulfillment completes,
- UI can observe asynchronous state transitions.

---

## Phase 3 — Extract Services

Extract:

- Order Service
- Inventory Service
- Payment Service
- Fulfillment Service

Each should:

- build independently,
- run independently,
- own its persistence boundary,
- communicate asynchronously for workflow transitions.

Avoid synchronous service-to-service REST calls unless there is a clear reason.

### Exit criteria

- services can be independently stopped/restarted,
- order processing still works after recovery,
- service boundaries are understandable.

---

## Phase 4 — Reliability

Implement:

- idempotent consumers,
- retry policy,
- dead-letter topics,
- duplicate scenario,
- poison-event scenario,
- consumer recovery scenario,
- inventory contention test.

### Exit criteria

Each advertised failure scenario is backed by an automated integration test.

---

## Phase 5 — Scenario-Oriented Frontend

Build the main portfolio UI.

Pages:

- Overview
- Orders
- Scenarios
- Scenario Run
- Event Explorer
- System Health
- Architecture

Add SSE/live updates.

### Exit criteria

A reviewer can understand and exercise the system without reading the source code.

---

## Phase 6 — Transactional Outbox

Address the database/Kafka dual-write problem.

Implement outbox behavior in at least the most important publisher, likely Order Service.

Document:

- original failure mode,
- new design,
- tradeoffs.

### Exit criteria

A business transaction and its intended event are durably coupled.

---

## Phase 7 — Containerization

Create Dockerfiles.

Create Docker Compose local stack.

### Exit criteria

A fresh clone can be started using documented commands.

---

## Phase 8 — Kubernetes

Deploy:

- frontend,
- Spring Boot services,
- supporting infrastructure where appropriate.

Add:

- Deployments,
- Services,
- ConfigMaps,
- Secrets,
- readiness/liveness probes,
- resource requests/limits.

### Exit criteria

The full app runs in Kubernetes.

---

## Phase 9 — Observability

Add:

- structured logs,
- correlation IDs,
- metrics,
- dashboarding,
- system health UI.

Optional:

- Prometheus,
- Grafana.

### Exit criteria

A scenario can be followed across services without guessing what happened.

---

## Phase 10 — Scaling Demonstration

Add high-volume scenario.

Run multiple consumer replicas.

Optional HPA.

Measure:

- event throughput,
- processing latency,
- consumer lag,
- replica behavior.

### Exit criteria

The project can demonstrate why Kafka consumer groups and Kubernetes scaling are useful.

---

## Phase 11 — Portfolio Polish

Improve:

- README,
- diagrams,
- architecture page,
- screenshots/GIFs,
- setup instructions,
- ADRs,
- resume bullets.

Do not let cosmetic work replace engineering completeness.

---
