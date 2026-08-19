# Order Fulfillment Systems Lab

An interactive, event-driven order-fulfillment sandbox — Java/Spring Boot, Kafka, PostgreSQL,
React/TypeScript. It's not a real storefront: the product is the distributed-systems demo (normal
order processing plus deliberately reproducible failure scenarios — outbox races, consumer
retries, DLQ routing, inventory contention), not the catalog around it. See
`docs/planning/project-overview.md` for the full purpose/scope statement, and `docs/planning/`
generally for the design docs behind everything in this repo.

## Prerequisites

- Docker (with Compose v2 — `docker compose`, not the standalone `docker-compose` binary)

That's the only hard requirement to run the whole stack. For the host-based dev workflow below
you'll also want Java 21 and Node 22, but not for the containerized path.

## Run the whole stack in Docker

From the repo root:

```bash
docker compose up -d --build
```

This builds and starts all 9 containers: Postgres, Kafka (single-node KRaft), the five backend
services (order, inventory, payment, fulfillment, scenario), the frontend, and Prometheus + Grafana
(Phase 9's observability stack). First build takes a few minutes (five separate Maven builds);
subsequent runs reuse Docker's layer cache.

Check everything came up healthy:

```bash
docker compose ps
```

Each backend service exposes health, metrics, and Prometheus endpoints on the host:

```bash
curl http://localhost:8081/actuator/health       # order-service
curl http://localhost:8081/actuator/metrics      # Micrometer metric names
curl http://localhost:8081/actuator/prometheus   # Prometheus scrape format
# same three paths on :8082 (inventory), :8083 (payment), :8084 (fulfillment), :8085 (scenario)
```

Then open the frontend:

**http://localhost:5173**

That's the lab's UI — an Overview page showing live service health, an Orders view, a Scenarios
page for triggering the reproducible failure/reliability demos, and an Event Explorer for watching
the Kafka events those scenarios actually produce (real requests and real events, not an animation).

### Metrics dashboards (Grafana)

**http://localhost:3000** — Grafana, provisioned with a Prometheus datasource
(**http://localhost:9090**, itself scraping all 5 backend services' `/actuator/prometheus` every
5s) and one dashboard ("Order Fulfillment Systems Lab — Overview": request rate, average latency,
Kafka consumer throughput, and JVM heap, per service). Anonymous viewer access is enabled, so the
dashboard is visible without logging in; the `admin`/`admin` credentials (dev-only, same posture
as this project's other local-only default credentials) are only needed to edit it.

### Tracing a scenario across services by correlation id

Every request/event in this system carries a `correlationId` (`X-Correlation-Id` HTTP header, or
generated fresh if absent — see `CorrelationIdFilter`/`CorrelationIdHolder` in `services/common`).
Every backend service logs structured JSON (Spring Boot's native ECS format —
`docs/adr/ADR-008-native-structured-logging.md`) with that id attached to every line logged while
handling that request or event. Trigger a scenario, grab its `correlationId` from the response,
then:

```bash
docker compose logs order-service inventory-service payment-service fulfillment-service scenario-service \
  | grep <correlation-id>
```

finds every hop of that workflow across all 5 services, in order.

Tear the stack down when you're done:

```bash
docker compose down       # stops containers, keeps the Postgres volume
docker compose down -v    # also wipes the Postgres volume — only if you want a clean slate
```

## Host-based dev workflow (iterating on one service)

This is the workflow every phase before this one used, and it still works unmodified — Docker
didn't change how any service's `application.yml` resolves `localhost`. Useful when you're actively
changing one service's code and want fast rebuild/restart without rebuilding its container image.

1. Start just the infra in Docker:

   ```bash
   docker compose up -d postgres kafka
   ```

2. Run whichever service(s) you're working on directly on the host, each in its own terminal:

   ```bash
   mvn -pl services/order-service -am spring-boot:run
   ```

   Repeat for `inventory-service`, `payment-service`, `fulfillment-service`, `scenario-service` as
   needed. These default to `localhost:5432` / `localhost:9092`, matching Kafka's host-side
   `HOST` listener (see "Kafka listeners" below) — no environment variables required.

3. Run the frontend with Vite's dev server:

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

   It defaults to `http://localhost:808X` for every backend, matching the ports above whether
   those services are running on the host or inside Docker.

You can also mix the two: run infra + some services in Docker (`docker compose up -d --build
order-service inventory-service`) and iterate on the rest on the host — the ports line up either
way.

## Kafka listeners

Kafka is configured with two listeners so both workflows above work against the same broker:

- `HOST` (`localhost:9092`) — for host-side tools: `mvn spring-boot:run`, this project's
  Testcontainers-based integration tests (`mvn test`), and any CLI you run from your laptop.
- `INTERNAL` (`kafka:29092`) — for the backend service containers talking to each other over the
  Docker Compose network, where `localhost` would otherwise resolve to the calling container
  itself rather than the broker.

See `docs/agent-reports/phase-7-containerization.md` for why a single `PLAINTEXT://localhost:9092`
listener (the pre-Phase-7 config) breaks once services run as separate containers.

## Run the whole stack in Kubernetes (local `kind`)

A third supported path, alongside (not replacing) the two above — see `docs/adr/ADR-007-kubernetes-only-after-local-boundaries-stabilize.md`
for why Kubernetes only shows up now, in Phase 8, and stays permanently optional. Plain YAML
manifests, no Helm, targeting local `kind` (not Minikube, not Docker Desktop Kubernetes).

Prerequisites: `kind` (`brew install kind`) and `kubectl`, plus Docker (already required above).

1. Create the cluster. `infrastructure/kind-config.yaml` maps the cluster's NodePorts onto the
   same host ports Compose already uses (8081-8085 for the backend services, 5173 for the
   frontend), so nothing downstream needs to know it's talking to Kubernetes instead of Compose.
   It lives one level above `infrastructure/kubernetes/` deliberately — that directory holds only
   real Kubernetes API objects, so `kubectl apply -f infrastructure/kubernetes/` (step 3) can apply
   the whole directory without tripping over a `kind`-only config file that isn't a k8s resource:

   ```bash
   kind create cluster --config infrastructure/kind-config.yaml
   ```

2. Build the 6 images with the `:local` tag `kind load` expects (same Dockerfiles as the Compose
   path, repo root as build context for the 5 backend services):

   ```bash
   for s in order inventory payment fulfillment scenario; do
     docker build -f services/${s}-service/Dockerfile -t ${s}-service:local .
   done
   docker build -t frontend:local frontend/
   ```

3. Load them into the cluster's node (`kind` clusters don't see your local Docker image cache) and
   apply the manifests:

   ```bash
   for img in order-service inventory-service payment-service fulfillment-service scenario-service frontend; do
     kind load docker-image "${img}:local" --name orderfulfillment
   done
   kubectl apply -f infrastructure/kubernetes/
   ```

4. Check everything came up:

   ```bash
   kubectl get pods -n orderfulfillment
   kubectl wait --for=condition=ready pod --all -n orderfulfillment --timeout=300s
   ```

   Same health endpoints as the Compose path, same host ports, now backed by the cluster:

   ```bash
   curl http://localhost:8081/actuator/health/liveness
   curl http://localhost:8081/actuator/health/readiness
   ```

   Frontend: **http://localhost:5173**

5. Tear down when you're done — a `kind` cluster is fully ephemeral, so this is a clean full wipe,
   not the "keep the volume" caution that applies to the Compose Postgres data:

   ```bash
   kind delete cluster --name orderfulfillment
   ```

See `docs/agent-reports/phase-8-kubernetes.md` for the manifest inventory, resource-sizing
reasoning, the Ingress-vs-NodePort call, and a live readiness-vs-liveness demonstration (including
a real finding: this app has no Kafka Actuator health indicator registered at all, so the
demonstration uses a Postgres outage instead, and the backend ConfigMaps add
`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db` — a manifest-level property
override, no application source touched — to make readiness actually reflect that dependency; see
that report for the full explanation and live numbers).

## Running tests

Tests are unaffected by any of the above — they still spin up their own Testcontainers-managed
Postgres/Kafka instances per service, independent of whatever `docker compose` state you have
running:

```bash
mvn -pl services/order-service test
# or, for every module:
mvn test
```
