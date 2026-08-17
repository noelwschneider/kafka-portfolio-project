
# 15. Kubernetes Design

Run application components in Kubernetes only after they work locally.

Potential deployment targets:

- local `kind`,
- Minikube,
- Docker Desktop Kubernetes,
- or a modest cloud cluster later.

## Kubernetes resources

For each service:

- Deployment
- Service
- ConfigMap
- Secret where needed
- readiness probe
- liveness probe
- resource requests/limits

Ingress:

- frontend
- API gateway or direct backend routes as appropriate

Optional later:

- HorizontalPodAutoscaler
- PodDisruptionBudget
- NetworkPolicy

Do not add advanced resources until the core behavior is working.

---

# 16. Health and Kubernetes Probes

Spring Boot Actuator should expose health information.

Conceptually distinguish:

## Liveness

> Is this process alive enough that Kubernetes should keep it running?

A failing liveness check may cause restart.

## Readiness

> Is this instance currently ready to receive traffic?

A failing readiness check should remove the pod from service traffic without necessarily restarting it.

Be prepared to explain why a temporarily unavailable dependency should not automatically imply that every liveness check must fail.

---

# 17. Docker and Local Development

Provide a straightforward local developer experience.

Recommended:

```bash
docker compose up
```

should start infrastructure such as:

- PostgreSQL
- Kafka
- optional Kafka UI
- optional observability stack

Application services may initially run directly from IDEs for easier debugging.

Later, support full container startup.

Each backend service should have its own Dockerfile.

The React frontend should also be containerized for the deployment phase.

---

# 18. Observability

Observability is part of the product, not an afterthought.

## Minimum requirements

### Structured logging

Every relevant log should include where possible:

- service name,
- order ID,
- correlation ID,
- event ID,
- event type.

### Spring Boot Actuator

Expose:

- health,
- metrics,
- info where useful.

### Correlation

Generate or propagate a `correlationId` across the full order workflow.

This should make it possible to trace a single order across services.

---

## Stronger later version

Add:

- Micrometer,
- Prometheus,
- Grafana.

Useful metrics:

- order creation count,
- successful fulfillment count,
- failed order count,
- event processing duration,
- retries,
- DLQ count,
- consumer lag,
- active pod replicas,
- HTTP latency/error rates.

OpenTelemetry tracing is a possible stretch goal, not an MVP requirement.

---

# 19. Testing Strategy

The tests should correspond directly to claims made in the UI and README.

## Unit tests

Test:

- validation,
- domain state transitions,
- inventory reservation rules,
- payment simulation rules,
- idempotency decisions,
- compensation behavior.

Use JUnit 5.

---

## Repository tests

Validate:

- JPA mappings,
- constraints,
- locking/version behavior,
- custom queries.

---

## Integration tests

Use Testcontainers where practical for:

- PostgreSQL,
- Kafka.

Test genuine producer/consumer flows rather than mocking Kafka everywhere.

Examples:

### Happy path integration test

1. Create order.
2. Wait for workflow.
3. Assert inventory reservation.
4. Assert payment.
5. Assert shipment.
6. Assert final order state.

### Duplicate event test

1. Publish event.
2. Publish same event ID again.
3. Assert only one side effect.

### Contention test

1. Configure limited inventory.
2. issue concurrent reservations.
3. assert stock invariants.

### DLQ test

1. Publish poison event.
2. await retries.
3. assert dead-letter arrival.

---

## API tests

Validate:

- response codes,
- request validation,
- error responses,
- expected asynchronous semantics.

---

## Frontend tests

At minimum:

- important components,
- scenario controls,
- order creation,
- timeline rendering.

Use React Testing Library or equivalent.

Avoid spending disproportionate effort on frontend snapshot tests.

---

## End-to-end tests

Later, add a small Playwright suite:

- create order,
- run happy-path scenario,
- verify final state,
- run one failure scenario.

---

# 20. API Error Model

Use a consistent API error response.

Example:

```json
{
  "timestamp": "2026-08-07T20:31:04.220Z",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_ORDER",
  "message": "Order must contain at least one item",
  "path": "/api/orders",
  "correlationId": "..."
}
```

Use centralized exception handling with Spring's controller advice mechanism.

---

# 21. Security Scope

Do not let authentication consume the project.

For an initial recruiter demo:

- anonymous/demo usage is acceptable,
- destructive scenario endpoints can be protected by a simple demo configuration,
- real payment/customer data is not present.

If authentication is added later, use standard Spring Security patterns.

Do not build custom cryptography or elaborate identity infrastructure for this project.

---

# 22. Repository Strategy

A monorepo is recommended for portfolio usability.

Example:

```text
order-fulfillment-lab/
|
├── README.md
├── docs/
│   ├── architecture.md
│   ├── events.md
│   ├── scenarios.md
│   ├── adr/
│   └── diagrams/
|
├── frontend/
│   └── ...
|
├── services/
│   ├── order-service/
│   ├── inventory-service/
│   ├── payment-service/
│   ├── fulfillment-service/
│   └── scenario-service/
|
├── infrastructure/
│   ├── docker/
│   ├── kubernetes/
│   └── observability/
|
├── scripts/
│   ├── seed-data/
│   ├── load-test/
│   └── demo/
|
└── .github/
    └── workflows/
```

Do not create every directory before it has a purpose.

---

# 23. Architecture Decision Records

Create brief ADRs for meaningful decisions.

Potential ADRs:

```text
ADR-001: Use Kafka for asynchronous order lifecycle events
ADR-002: Keep demo/fault injection APIs separate from business APIs
ADR-003: Use SSE rather than WebSockets for live frontend updates
ADR-004: Use PostgreSQL per-service ownership boundaries
ADR-005: Use idempotent consumers for duplicate delivery
ADR-006: Add transactional outbox for DB/Kafka consistency
ADR-007: Use Kubernetes only after local service boundaries stabilize
```

Each ADR should answer:

- context,
- decision,
- alternatives considered,
- consequences/tradeoffs.

These are especially useful as interview preparation.

---

# 24. CI/CD

Use GitHub Actions.

## Pull request pipeline

Run:

- backend formatting/linting as configured,
- backend unit tests,
- integration tests where practical,
- frontend lint,
- TypeScript checks,
- frontend tests,
- production builds.

---

## Main branch pipeline

Possible later stages:

1. run tests,
2. build Java artifacts,
3. build frontend,
4. build Docker images,
5. tag images,
6. push to registry,
7. deploy/update Kubernetes manifests,
8. run smoke tests.

Do not make sophisticated continuous deployment mandatory for the first version.

A clean CI pipeline alone is already valuable.

---
