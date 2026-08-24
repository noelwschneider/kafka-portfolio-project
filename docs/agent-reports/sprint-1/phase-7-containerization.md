# Phase 7 — Containerization

**Scope:** Dockerfiles for the 5 backend services + frontend, an updated root `docker-compose.yml`
that runs the whole stack, and a root `README.md`. Gate: fresh clone runs via documented
`docker compose up`, without breaking the existing host-based dev/test workflow.

---

## 1. Kafka's dual-listener fix

Pre-Phase-7, `docker-compose.yml` advertised Kafka as `PLAINTEXT://localhost:9092`. That's fine
when every client runs on the host (the workflow through Phase 6), but breaks the moment a client
runs inside another container: it connects to the bootstrap address `kafka:9092` fine, but the
broker's metadata response then tells it to reconnect to `localhost:9092` for the actual
produce/consume — which from inside, say, `order-service`'s container resolves to that container
itself, not the broker.

Fix: two listeners on the single KRaft node.

```yaml
KAFKA_LISTENERS: INTERNAL://0.0.0.0:29092,HOST://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,HOST://localhost:9092
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,HOST:PLAINTEXT
KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
```

`HOST` (`localhost:9092`) is unchanged from before this phase, so `mvn spring-boot:run` and this
project's Testcontainers-based integration tests keep working with zero config changes. `INTERNAL`
(`kafka:29092`) is what the 5 backend containers use, via `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` set
per-service in compose — the port is already externalized in every `application.yml`
(`${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`), so this needed no code or config file changes, only
new environment overrides in compose.

Verified for real, not just by reading the config: brought the full stack up and ran the
`standard-order` scenario end-to-end (`POST /demo/scenarios/standard-order` →
`GET /demo/scenario-runs/{id}`), which produces and consumes on `orders.events`, `payments.events`,
and `fulfillment.events` across 4 separate containers and reached `FULFILLED`. See §5.

Also added a Kafka healthcheck (`kafka-broker-api-versions.sh` against the `HOST` listener) — the
KRaft process can be "up" for several seconds before it's actually accepting client connections,
and the pre-existing Postgres healthcheck pattern implied this should exist for Kafka too. Backend
services' `depends_on: kafka: condition: service_healthy` blocks on it.

## 2. Judgment call: the whole `services/` tree goes into every backend's build context

Confirmed empirically (not assumed) that Maven's reactor requires every module directory listed in
the root `pom.xml`'s `<modules>` to physically exist on disk, even when `-pl -am` restricts which
modules actually build — `mvn -pl services/common,services/order-service -am` fails immediately
with "Child module .../services/inventory-service does not exist" if that directory is simply
absent from the build context. Ran this as a throwaway test in `/tmp` with just the 5 `pom.xml`
files before writing the Dockerfiles, rather than discovering it partway through a real build.

Consequence: each of the 5 backend Dockerfiles uses the repo root as its build context (`context:
.` in compose) and does `COPY services services` — the full tree, not just `services/common` and
its own module — then `mvn -pl services/common,services/<name> -am package -DskipTests` filters
which modules actually compile. `.dockerignore` strips `**/target/` so this doesn't balloon the
context with build artifacts, but each service's Dockerfile still invalidates its Docker layer
cache on any change anywhere under `services/`, not just its own module. Given this is 5 small
Spring Boot services and Maven's own local-repo cache (`~/.m2`, not volume-mounted here) is what
actually saves rebuild time on unchanged dependencies, this was judged not worth the complexity of
a shared intermediate build image or a `.m2` cache mount — acceptable for a portfolio-scale project,
called out here in case it matters at a different scale.

One packaging detail worth flagging: `spring-boot-maven-plugin`'s repackage leaves both `app.jar`
(executable) and `app.jar.original` (plain classes jar) in `target/`, so the naive `COPY --from=build
.../target/*.jar app.jar` glob matched two files and failed. Fixed by `rm -f
services/<name>/target/*.jar.original` before the final `COPY`.

## 3. Image base choices

- Build stage: `maven:3.9-eclipse-temurin-21` — pinned Java 21 per the project's stack table,
  standard Maven image, no need to install a JDK separately.
- Runtime stage: `eclipse-temurin:21-jre-alpine` — JRE only (no JDK, no Maven) and Alpine for a
  small final image, since none of the 4 services need anything beyond `java -jar`. It also ships
  BusyBox `wget`, which the compose healthchecks use to hit `/actuator/health` from inside each
  container without adding curl.
- Frontend build stage: `node:22-alpine`, matching the project's pinned Node 22 LTS.
- Frontend runtime stage: `nginx:alpine` — plain static file serving, no reverse-proxy
  configuration, per the brief's explicit steer against over-building this (the SPA calls backend
  URLs directly from the browser; see §4).

## 4. Frontend: no Docker-network awareness needed

The frontend container only builds and serves static assets. Browser-side `fetch()` calls use
`VITE_ORDER_SERVICE_URL` etc., which default to `http://localhost:808X` (`frontend/src/api/client.ts`)
and are baked in at `npm run build` time. Since `docker-compose.yml` publishes every backend's port
to the host (`"8081:8081"` etc.), those defaults resolve correctly whether the browser is talking to
a backend running in Docker or on the host — no build-time `VITE_*` overrides were needed in compose,
and none were added. Verified by loading `http://localhost:5173` after `docker compose up` and
confirming the page (and, via the scenario run in §5, the underlying API calls) works.

## 5. Verification results

Ran `docker compose up -d --build` from a clean state (no prior images) and checked:

| Check | Result |
|---|---|
| `docker compose ps` — all 7 containers | Pass. postgres, kafka healthy within ~30s; order/inventory/payment/fulfillment-service healthy within ~50s; scenario-service (depends on all 4) healthy at ~70s; frontend up at ~75s. |
| `docker compose logs` — no startup errors | Pass. Flyway migrations applied on all 4 services with per-service schemas; the only non-INFO-adjacent noise was expected Kafka consumer-group `NotCoordinatorException`/`RebalanceInProgressException` INFO-level rediscovery churn during the single-node KRaft cluster's cold start — no ERROR-level log lines. |
| `curl http://localhost:808{1..5}/actuator/health` | Pass — all 5 report `{"status":"UP", ...}`. |
| Frontend loads at `http://localhost:5173` | Pass — HTTP 200, correct `<title>Order Fulfillment Systems Lab</title>`, built JS/CSS assets served. |
| Real scenario run end-to-end | Pass. `POST /demo/scenarios/standard-order` → polled `GET /demo/scenario-runs/{id}` until `COMPLETED`: order created, `INVENTORY_RESERVED` → `PAYMENT_PENDING` → `PaymentRequested`/`PaymentAuthorized` over `orders.events`/`payments.events` → `PAID` → `FULFILLMENT_PENDING` → `ShipmentCreated` over `fulfillment.events` → `FULFILLED`. Confirms both the Kafka dual-listener fix and scenario-service's server-to-server HTTP calls to the other 4 containers. |
| `mvn -pl services/order-service -am test` on host | Pass — `Tests run: 25, Failures: 0, Errors: 0`, `BUILD SUCCESS`, ~2 min via the service's own Testcontainers-managed Postgres/Kafka. No `application.yml` was touched by this phase, so this confirms the host dev/test flow is unmodified. |
| Teardown | `docker compose down` (no `-v`) — Postgres volume preserved per project convention. |

## 6. Not in scope

Per the brief and `docs/planning/execution-plan.md`'s note that CI/registry publishing is a later
phase's concern: no GitHub Actions image build/push, no ghcr.io wiring. This phase's gate is local
`docker compose up` only.
