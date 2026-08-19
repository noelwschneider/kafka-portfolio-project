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

This builds and starts all 7 containers: Postgres, Kafka (single-node KRaft), the five backend
services (order, inventory, payment, fulfillment, scenario), and the frontend. First build takes a
few minutes (five separate Maven builds); subsequent runs reuse Docker's layer cache.

Check everything came up healthy:

```bash
docker compose ps
```

Each backend service exposes a health endpoint on the host:

```bash
curl http://localhost:8081/actuator/health   # order-service
curl http://localhost:8082/actuator/health   # inventory-service
curl http://localhost:8083/actuator/health   # payment-service
curl http://localhost:8084/actuator/health   # fulfillment-service
curl http://localhost:8085/actuator/health   # scenario-service
```

Then open the frontend:

**http://localhost:5173**

That's the lab's UI — an Overview page showing live service health, an Orders view, a Scenarios
page for triggering the reproducible failure/reliability demos, and an Event Explorer for watching
the Kafka events those scenarios actually produce (real requests and real events, not an animation).

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

## Running tests

Tests are unaffected by any of the above — they still spin up their own Testcontainers-managed
Postgres/Kafka instances per service, independent of whatever `docker compose` state you have
running:

```bash
mvn -pl services/order-service test
# or, for every module:
mvn test
```
