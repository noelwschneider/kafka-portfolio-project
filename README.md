# Order Fulfillment Systems Lab

An interactive, event-driven order-fulfillment sandbox — Java/Spring Boot, Kafka, PostgreSQL,
React/TypeScript. It's not a real storefront: the product is the distributed-systems demo (normal
order processing plus deliberately reproducible failure scenarios — outbox races, consumer
retries, DLQ routing, inventory contention), not the catalog around it. See
`docs/planning/project-overview.md` for the full purpose/scope statement, and `docs/planning/`
generally for the design docs behind everything in this repo.

## Live demo

**https://fulfillment-demo.noelschneider.com**

Know what you are clicking: it is a **shared public sandbox**. Anyone can run the failure scenarios,
several people can be running them at once, and the system resets itself after a period of
inactivity — so inventory levels, orders and scenario runs may change or disappear while you are
looking at them. That is the demo working as intended, not a bug. Everything below runs the same
system locally, where the only person changing state is you.

## Prerequisites

- Docker (with Compose v2 — `docker compose`, not the standalone `docker-compose` binary)

That's the only hard requirement to run the whole stack. For the host-based dev workflow below
you'll also want Java 21 and Node 22, but not for the containerized path.

## Run the whole stack in Docker

From the repo root:

```bash
docker compose up -d --build
```

This builds and starts all 10 containers: Postgres, Kafka (single-node KRaft), the five backend
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

(That's why a single `PLAINTEXT://localhost:9092` listener, the pre-Phase-7 config, stopped working
once services moved into separate containers: `localhost` inside a container resolves to that
container itself, not the broker.)

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

   `infrastructure/kubernetes/` holds only what this local flow needs. The public demo box runs the
   same manifests through an additive overlay in `infrastructure/kubernetes/production/`, which adds
   a Traefik ingress whose path allowlist replaces the NodePorts (they become `ClusterIP` there),
   generates the Postgres password at apply time instead of using the committed dev one, and tunes
   heaps and probes for a 2-vCPU box. Nothing in that directory affects the commands above —
   `kubectl apply -f infrastructure/kubernetes/` is unchanged by its existence. See that directory's
   README, and `docs/adr/ADR-010-k3s-on-a-dedicated-hetzner-vps-for-the-public-demo.md` for why the
   deployment looks the way it does.

5. Tear down when you're done — a `kind` cluster is fully ephemeral, so this is a clean full wipe,
   not the "keep the volume" caution that applies to the Compose Postgres data:

   ```bash
   kind delete cluster --name orderfulfillment
   ```

A real finding from bringing this up: this app has no Kafka Actuator health indicator registered at
all, so readiness can't reflect a Kafka outage directly. The backend ConfigMaps add
`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db` — a manifest-level property
override, no application source touched — so readiness at least reflects the Postgres dependency,
and a Postgres outage (rather than a Kafka one) is what actually demonstrates the
readiness-vs-liveness distinction live.

Manual horizontal scaling of a backend service (rather than the frontend or one-off jobs) is worth
trying directly against the running consumer groups:

```bash
kubectl scale deployment/inventory-service --replicas=2 -n orderfulfillment
```

then trigger Scenario 8 (High-Volume Batch, `POST /demo/scenarios/high-volume`) and watch consumer
lag on `inventory-service`'s `orders.events` group change as replica count changes — this is the
mechanism behind Kafka consumer-group parallelism, not a simulation. A `HorizontalPodAutoscaler`
(`infrastructure/kubernetes/10-inventory-service-hpa.yaml`, fed by `metrics-server`) also scales
Inventory Service automatically between 1 and 3 replicas on CPU utilization — see
`docs/architecture-diagram.md`'s Scaling section for a real, measured scale-up/scale-down run. Manual
`kubectl scale` remains useful for triggering a specific replica count on demand.
On this project's own development machine, only 1 and 2 Inventory Service replicas were actually
measured — a 3rd replica hit a local Docker Desktop VM resource ceiling (CPU/memory contention
across the whole cluster's pods, not a defect in the manifests or the scaling mechanism itself) and
was not obtained. Your hardware may do better.

## Running tests

Tests are unaffected by any of the above — they still spin up their own Testcontainers-managed
Postgres/Kafka instances per service, independent of whatever `docker compose` state you have
running:

```bash
mvn -pl services/order-service test
# or, for every module:
mvn test
```

## What this project demonstrates

Verified against the implementation as of this writing, not aspirational (`docs/planning/engineering-rules.md`
rule 18 — no claim here is stronger than what's actually built):

- Designed and built an event-driven order fulfillment platform (Java 21, Spring Boot, Apache Kafka,
  PostgreSQL, React/TypeScript) that separates inventory, payment, and fulfillment workflows into
  independently deployable services communicating only through asynchronous domain events — no
  synchronous service-to-service REST calls in the workflow (ADR-002, `docs/architecture-diagram.md`).
- Implemented idempotent Kafka consumers (a per-service `processed_events` ledger, ADR-005), bounded
  retry with backoff, dead-letter routing on exhausted retries, and correlation-ID-based tracing
  across all five services (ADR-008) to make asynchronous failures diagnosable rather than silent.
- Designed concurrent inventory reservation logic, backed by integration tests, that prevents
  overselling under competing concurrent order requests.
- Implemented a transactional outbox (ADR-006) to close the database/Kafka dual-write gap for every
  publisher's own event publication — durable, not exactly-once. Shipped for Order Service first,
  then extended to Inventory, Payment, and Fulfillment Service, so all four business services now
  publish through the same pattern with no remaining dual-write window.
- Found and fixed a real out-of-order-delivery correctness bug (ADR-009): two independently-consumed
  Kafka topics writing the same order's status could interleave and corrupt its state under load.
  Fixed with an explicit state-transition guard, a deferred-transition queue, and per-order
  serialization — reproduced deterministically in an integration test before and after the fix.
- Containerized all five backend services and the frontend (Docker), and deployed them to Kubernetes
  (`kind`) with Deployments, Services, ConfigMaps, Secrets, and readiness/liveness probes wired to
  each service's actual dependencies. Demonstrated manual horizontal scaling of a Kafka consumer
  group (`kubectl scale`) with real, measured throughput and consumer-lag numbers at 1 and 2
  replicas — no `HorizontalPodAutoscaler` is configured, so "scalable" here means the manifests and
  consumer-group mechanics support it, demonstrated both manually and via a `HorizontalPodAutoscaler`
  on Inventory Service.
- Built an interactive engineering console (React/TypeScript) that triggers these scenarios as real
  HTTP requests against the running services and visualizes actual resulting state, Kafka events,
  and SSE-pushed status changes — not a frontend animation (rule 10).

Not yet built, stated plainly rather than left implicit: full CI/CD. A GitHub Actions workflow
(`.github/workflows/build-images.yml`) exists, but it only builds and publishes the six container
images to GHCR on manual `workflow_dispatch` — there is no workflow that runs tests on push/PR, and
deployment to the public demo stays a manual `kubectl apply` rather than something the pipeline does
(`docs/adr/ADR-010-k3s-on-a-dedicated-hetzner-vps-for-the-public-demo.md`).
