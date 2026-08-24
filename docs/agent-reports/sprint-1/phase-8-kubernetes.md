# Phase 8 — Kubernetes

**Scope:** plain-YAML Kubernetes manifests under `infrastructure/kubernetes/` for the 5 backend
services, Postgres, Kafka, and the frontend, targeting local `kind`, per
`docs/adr/ADR-007-kubernetes-only-after-local-boundaries-stabilize.md`. Gate: a fresh `kind`
cluster runs the whole stack from documented commands, with readiness and liveness proven to behave
differently for real, not just configured to look different. No Helm, no HPA/PDB/NetworkPolicy/mesh
(explicitly deferred).

---

## 1. Files created

All under `infrastructure/kubernetes/`, applied in numeric order (`kubectl apply -f
infrastructure/kubernetes/` applies the whole directory; `kubectl` doesn't guarantee file order
within one invocation, but every object here tolerates out-of-order creation — Deployments that
reference a not-yet-existing ConfigMap/Secret just wait in `ContainerCreating` until the referenced
object shows up, and namespaced objects fail closed until the namespace exists rather than
erroring destructively):

| File | Contents |
|---|---|
| `00-namespace.yaml` | `orderfulfillment` namespace — nothing in this phase runs in `default`. |
| `01-secrets.yaml` | `postgres-credentials` Secret (`POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB`), routed via `secretKeyRef`/`envFrom` rather than inlined — see §3. |
| `02-postgres.yaml` | PVC + single-replica Deployment (`strategy: Recreate`) + Service for `postgres:16-alpine`. |
| `03-kafka.yaml` | PVC + single-replica Deployment (`strategy: Recreate`) + Service for `apache/kafka:4.0.0`, single-node KRaft broker+controller, dual-listener pattern adapted to in-cluster DNS. |
| `04`–`08` (`order`/`inventory`/`payment`/`fulfillment`/`scenario`-service.yaml) | Per service: a ConfigMap (non-secret env), a Deployment (readiness/liveness probes, resource requests/limits, DB credentials via `secretKeyRef`), and a NodePort Service. |
| `09-frontend.yaml` | Frontend Deployment + NodePort Service, reusing the unmodified Phase 7 image. |

`infrastructure/kind-config.yaml` (one level up, deliberately outside `infrastructure/kubernetes/`)
holds the `kind` cluster config with `extraPortMappings` from the control-plane node's NodePorts
back onto host `8081`-`8085` and `5173`. It started out inside `infrastructure/kubernetes/`
alongside the manifests, but `kubectl apply -f infrastructure/kubernetes/` applies every file in
that directory, and a `kind.x-k8s.io/v1alpha4` `Cluster` object isn't a real Kubernetes API
resource — `kubectl apply` errors on it (`no matches for kind "Cluster"`). Moving it out keeps
`infrastructure/kubernetes/` apply-the-whole-directory-safe, which is what the README's documented
command actually does.

Each backend service's ConfigMap carries `SPRING_DATASOURCE_URL` (`jdbc:postgresql://postgres:5432/orderfulfillment`),
`KAFKA_BOOTSTRAP_SERVERS` (`kafka:29092`), and `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE`
(`readinessState,db` — see §7 for why) — all non-secret, all just Spring Boot property overrides or
in-cluster Service DNS names, the direct K8s equivalent of `docker-compose.yml`'s per-service env
block. `scenario-service`'s ConfigMap additionally carries
`ORDER_SERVICE_URL`/`INVENTORY_SERVICE_URL`/`PAYMENT_SERVICE_URL`/`FULFILLMENT_SERVICE_URL`,
translating Compose's service-name URLs (`http://order-service:8081` etc.) one-for-one — Kubernetes
Service DNS resolves the same short names within one namespace, so no rewriting was needed beyond
copying the values across. `SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD` come from the
Secret via `secretKeyRef` on every backend Deployment — confirmed against each service's
`application.yml` (`spring.datasource.username`/`password`, which Spring Boot's relaxed binding maps
from those exact env var names) rather than assumed.

## 2. Ingress vs. NodePort

Went with NodePort, not Ingress. `docs/planning/high-level-design.md`'s Kubernetes Design section
lists Ingress separately from the core per-service resource list (Deployment/Service/ConfigMap/
Secret/probes/resources), and this phase's gate only requires the core list — Ingress is explicitly
optional/judgment-call territory here, not a frozen requirement.

Reasoning for skipping it in Phase 8 specifically: the frontend is a browser-side SPA that calls
`VITE_ORDER_SERVICE_URL` etc. directly from `fetch()`, baked in at Vite build time to
`http://localhost:808X` (`frontend/.env.example`, unchanged since Phase 7). An Ingress would need
either (a) a rebuild of the frontend image with different build-time URLs pointed at Ingress paths,
reintroducing exactly the Compose/Kubernetes environment divergence ADR-007 and the Phase 7 report
were careful to avoid, or (b) a reverse-proxy rewrite layer that doesn't exist yet. NodePort sidesteps
both: `kind-config.yaml`'s `extraPortMappings` map the cluster's NodePorts (`30081`-`30085`, `30173`)
straight onto the same host ports Compose already publishes (`8081`-`8085`, `5173`), so the exact
same frontend image built once in Phase 7 works against the `kind` cluster with zero rebuild and zero
env changes — verified in §5, not assumed. An Ingress controller (ingress-nginx or similar) is real
extra surface — another Deployment, another set of manifests, a controller-specific annotation
dialect — that this phase's gate doesn't require and that a NodePort mapping makes unnecessary for a
single-cluster local demo. Worth adding later if/when a path-based single-entrypoint story becomes
part of the portfolio narrative; not before.

## 3. Secrets

Only `POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB` are genuinely credential-shaped, so only
those live in `01-secrets.yaml`'s `postgres-credentials` Secret. Postgres itself consumes the whole
Secret via `envFrom.secretRef` (matches its expected `POSTGRES_*` env var names directly); the 5
backend services instead cherry-pick `SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD` from
the same Secret via individual `secretKeyRef` entries, because Spring's env var names differ from
Postgres's own. Everything else (JDBC URL, Kafka bootstrap address, downstream service URLs) is a
hostname/port, not a credential, and lives in ConfigMaps instead — these are trivial dev credentials
either way (`orderfulfillment`/`orderfulfillment`, identical to `docker-compose.yml`), so the point
of routing through `secretKeyRef` rather than inlining plaintext env values in the Deployments is
demonstrating the primitive correctly, not actually protecting anything sensitive.

## 4. In-cluster Postgres and Kafka

**Postgres:** plain single-replica Deployment + PVC, not a StatefulSet — per this phase's brief and
ADR-007, a StatefulSet's stable network identity and ordered multi-replica rollout guarantees buy
nothing for one pod with one PVC and no replication story. `strategy: Recreate` is set explicitly:
with the default `RollingUpdate` strategy, `kubectl apply` on a future manifest change would try to
start a second pod while the first still holds the `ReadWriteOnce` PVC and hang; `Recreate` matches
the single-instance reality by tearing the old pod down before starting the new one.

**Kafka:** same single-node KRaft broker+controller topology as `docker-compose.yml`, same
`apache/kafka:4.0.0` image, same core env var set. Two adaptations from the Compose config:

- `KAFKA_ADVERTISED_LISTENERS`'s `INTERNAL` address changes from `kafka:29092` (Compose's
  network-scoped DNS) to `kafka.orderfulfillment.svc.cluster.local:29092` (the fully-qualified
  in-cluster Service DNS name; the short form `kafka:29092` also resolves for any client in the same
  namespace, which is what the 5 backend ConfigMaps actually use).
- `KAFKA_CONTROLLER_QUORUM_VOTERS` stays `1@localhost:9093`, unchanged from Compose. This was the one
  value the brief flagged to verify empirically rather than assume, since it's tempting to reflexively
  cluster-DNS-ify every address in this block. Broker and controller are the same process in this
  single-node pod, talking to each other over loopback inside the one container — there is no second
  pod to reach, so `localhost` is correct and confirmed working (the cluster came up, topics
  auto-created, and the full scenario run in §6 produced/consumed across all 3 event topics).

## 5. Resource requests/limits

Local single-node `kind` demo, not a sizing exercise for a real deployment — the goal was real,
defensible numbers in the brief's suggested band, not copy-pasted zeros or padding:

| Component | Requests (cpu/mem) | Limits (cpu/mem) | Reasoning |
|---|---|---|---|
| Postgres | 100m / 256Mi | 500m / 512Mi | Single-connection-pool-scale local Postgres; Alpine image, small working set for this project's per-service schema sizes. |
| Kafka | 250m / 512Mi | 1 / 1Gi | JVM broker + controller in one process needs more headroom than the Spring Boot services — request sized above the JVM's typical idle heap, limit gives room for GC pauses and topic/partition metadata under the scenario runs' event volume. |
| Each backend service (order/inventory/payment/fulfillment/scenario) | 150m / 320Mi | 500m / 640Mi | Ordinary Spring Boot service: JVM startup briefly spikes CPU (Flyway + Hibernate + Kafka consumer bootstrap), then settles low; 320Mi request covers typical idle heap + off-heap metaspace for one of these small services, 640Mi limit leaves room without inviting a Kafka-consumer-buffer-driven OOM kill under scenario load. |
| Frontend | 50m / 64Mi | 250m / 128Mi | Static `nginx` file serving only — no app logic, no JVM. |

## 6. Verification results

All run in one continuous session against a real `kind` cluster (`orderfulfillment`), foreground,
no backgrounded builds.

| Check | Result |
|---|---|
| `kind` installed | Not present; installed via `brew install kind` (0.32.0). `kubectl` was already present. |
| `kind create cluster --config infrastructure/kind-config.yaml` | This sandbox's Docker Desktop VM is capped at 3.825GiB total memory, tight for a `kind` control-plane node plus 6 JVM-based pods. Pre-pulling the `kindest/node` image separately (rather than letting `kind create cluster` pull it inline under its own timeout) made cluster creation itself reliable. The same memory ceiling showed up again later: a `kubectl rollout restart` across all 5 backend Deployments at once (to pick up a ConfigMap change, see below) briefly ran old+new ReplicaSet pods side by side and pushed memory requests to 85% allocated, causing probe timeouts, a few transient restarts, and two pods stuck `Pending` on "Insufficient memory." Recovered by scaling all 5 backend Deployments to 0, letting the node settle, then scaling them back up one at a time (`kubectl wait --for=condition=ready` between each) — clean thereafter, 0 restarts on the final pod set. Worth knowing for this specific sandbox's resource ceiling; not a manifest defect (the resource requests themselves are the ones documented in §5, and a real machine with more headroom wouldn't hit this). |
| 6 images built (`order/inventory/payment/fulfillment/scenario-service:local`, `frontend:local`) | Pass — all built from the unmodified Phase 7 Dockerfiles, repo root as build context for the 5 backend services per their existing `COPY services services` + `mvn -pl ... -am` pattern. |
| `kind load docker-image <name>:local --name orderfulfillment` × 6 | Pass, all 6 loaded. |
| `kubectl apply -f infrastructure/kubernetes/` | Pass — namespace, secret, both stateful components, all 5 backend services, frontend all created/reconciled. |
| `kubectl wait --for=condition=ready pod --all -n orderfulfillment --timeout=300s` | Pass — all 8 pods (`postgres`, `kafka`, 5 backend services, `frontend`) reached `Ready` (`1/1`), not just `Running`. |
| `/actuator/health/liveness` and `/actuator/health/readiness` on all 5 backend services, via the NodePort→host-port mapping (`localhost:8081`-`8085`) | Pass — all 10 checks returned `{"status":"UP"}`. |
| Frontend reachable | Pass — `http://localhost:5173` returns HTTP 200 and the built SPA shell. |
| Full scenario run through the cluster | Pass. `POST http://localhost:8085/demo/scenarios/standard-order` (run id `run-101`) → polled `GET /demo/scenario-runs/run-101` (the correct path — not `/demo/scenarios/runs/{id}`) until the scenario run's own `status` field reached `COMPLETED`, with the underlying order (`order-20000`, `GET /api/orders/order-20000`) reaching `FULFILLED` in its `statusHistory`: `PENDING` → `INVENTORY_RESERVED` → `PAYMENT_PENDING` → `PAID` → `FULFILLMENT_PENDING` → `FULFILLED`. The run's timeline shows the same trip across real topics: `OrderCreated` on `orders.events` → `InventoryReserved` on `inventory.events` → `PaymentRequested`/`PaymentAuthorized` on `orders.events`/`payments.events` → `ShipmentCreated` on `fulfillment.events`. Confirms real HTTP calls, real Kafka production/consumption, and real persistence across all 5 containers running as separate pods, not a simulation. |
| `git status`/`git diff --stat` for `services/*/src` and `frontend/src` | Clean — this phase touched only `infrastructure/`, `docs/adr/ADR-007-...md`, `README.md`, and this report. No application source was modified. |
| Teardown | `kind delete cluster --name orderfulfillment` — full wipe, confirmed via `kind get clusters` (empty) and `docker ps -a` (no leftover containers). Correct here, unlike the Compose Postgres volume convention: a `kind` cluster is fully ephemeral and nothing existed before this phase created it, so nothing is being preserved-by-default and then exceptionally wiped. |

## 7. Live readiness-vs-liveness demonstration — the finding, the fix, and the actual numbers

The first pass at this demonstration (scaling Kafka to 0 and watching `order-service`) found a real
gap rather than the expected asymmetry: **readiness never flipped false**, even after 3+ minutes of
continuous, logged Kafka reconnect failures. Investigating rather than papering over it:

- Every service's `application.yml` sets `management.endpoint.health.probes.enabled: true`, which
  creates the `liveness`/`readiness` health groups Kubernetes probes hit — but none of them set
  `management.endpoint.health.group.readiness.include`, so Spring Boot's default `readiness` group
  contains *only* the built-in `readinessState` contributor (an app-lifecycle flag: "has this JVM
  finished starting"), never a dependency check.
- Turning on `management.endpoint.health.show-details=always` temporarily (a live env-var probe, not
  a file change) revealed the deeper reason a Kafka-based demo can't work here at all: this
  application's `/actuator/health` component list is exactly `db`, `diskSpace`, `livenessState`,
  `ping`, `readinessState`, `ssl` — **there is no `kafka` health contributor registered**, in this
  Spring Boot version/dependency combination, even in principle. Confirmed by trying to add
  `kafka` to a readiness group's `include` list directly: the app fails to start with `Health
  contributor 'kafka' defined in 'management.endpoint.health.group.readiness.include' does not
  exist`. So no property override alone can wire Kafka into readiness — that would need actual
  application code (a custom `HealthIndicator` bean), which is out of scope for this phase.

**Fix applied, staying within this phase's scope (Kubernetes manifests, not application source):**
`db` *is* a real, auto-registered dependency health indicator (Postgres connectivity, via
`DataSourceHealthIndicator`). Every backend ConfigMap (`04`-`08-*.yaml`) now sets
`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db` — a Spring Boot property
override via env var (relaxed binding), applied entirely from the Deployment's ConfigMap, no line of
`application.yml` touched. This makes the readiness-vs-liveness distinction genuinely observable
using a Postgres outage instead of a Kafka one — a legitimate substitution of *which* dependency is
demonstrated, not a fake of the underlying mechanism.

**Live demonstration, run twice for consistency, both against `order-service`:**

| Run | Pod | Postgres scaled to 0 | Readiness failed (NotReady) | Restart count during outage | Postgres scaled back to 1 | Postgres pod Ready | Readiness recovered | Restart count after |
|---|---|---|---|---|---|---|---|---|
| 1 | `order-service-744d99fb58-g45hn` | 14:05:15 | 14:05:48 (+33s) | 0 | 14:06:35 | 14:06:42 (+7s) | 14:06:42 (same check) | 0 |
| 2 | `order-service-5bc69ff6b6-fn84s` | 14:26:21 | 14:26:55 (+34s) | 0 | 14:27:03 | 14:27:10 (+7s) | 14:27:13 (+3s) | 0 |

Both runs: the moment readiness failed, `kubectl get endpoints order-service` showed an **empty**
endpoints list (pod removed from Service load-balancing) — traffic genuinely stops reaching it, not
just a status flag flipping. Restart count was checked before scaling down, immediately after
readiness failed, and again after recovery — **0 the entire time, both runs**: liveness never
fired, exactly as ADR-007 specifies ("restarting a healthy pod does not fix a dependency"). The
~33-34s time-to-NotReady matches the readiness probe's `initialDelaySeconds: 30` plus one
`periodSeconds: 5` failing check; recovery took 3-7 seconds after Postgres itself became Ready — well
under one more probe interval.

This is the real, live-proven version of the distinction the phase set out to demonstrate: a
temporarily-down dependency removes the pod from traffic without restarting it, and recovery is
automatic and fast once the dependency returns — using Postgres as the dependency instead of Kafka,
because Kafka has no registered health indicator in this application at all (a fact worth knowing
independent of this phase, and flagged as a possible small follow-up: add a custom Kafka
`HealthIndicator` bean if a Kafka-outage-specific readiness signal is ever wanted, which would be an
application-code change, correctly out of scope here).

## 8. Not in scope / correctly deferred

Per ADR-007 and this phase's brief: no HorizontalPodAutoscaler, no PodDisruptionBudget, no
NetworkPolicy, no service mesh, no Helm, no Ingress (see §2), no CI/registry wiring for these images.
