# Chapter 7 — Containers and Kubernetes

**Build history:** Phase 7 (`4f3b07c containerization`) and Phase 8 (`fb5caab kubernetes`).

Two phases with one theme: the system stops being something you run from an IDE and becomes something
you deploy. Nothing about the application changes — which is the point, and the return on a discipline
kept since [Chapter 2](../02-domain/1-project-skeleton.md) with nothing verifying it.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Containers and Compose](1-containers-and-compose.md) | Multi-stage Dockerfiles, the monorepo build-context problem, why a browser SPA needs build-time configuration, the nginx SPA fallback, and Kafka's dual-listener arrangement |
| 2 | [Kubernetes manifests](2-kubernetes-manifests.md) | Plain YAML over Helm, one file per service, ConfigMaps vs. Secrets, `NodePort` and what it exposes, PostgreSQL without a StatefulSet, and what Phase 8 deferred |
| 3 | [Probes and resources](3-probes-and-resources.md) | Readiness vs. liveness, the finding that default readiness reflected nothing, requests vs. limits and the JVM, and running on kind |

---

## The exit criteria

**Phase 7:** *A fresh clone can be started using documented commands.*
**Phase 8:** *The full app runs in Kubernetes.*

Both are satisfied by `docker compose up --build` and a four-command kind sequence — and neither
required an application source change.

---

## Three ideas worth carrying out

**A discipline kept for six chapters gets paid back in one.** Every environment-varying value has been
`${VAR:local-default}` since [Chapter 2](../02-domain/1-project-skeleton.md), on ADR-007's warning that
otherwise *"Phase 7 turns into a refactor."* The bill turns out to be two environment variables per
service, and the same image runs under Compose and under Kubernetes with no knowledge of either.

**Where configuration is read decides how it is packaged.** A backend reads config at startup, so one
image serves every environment. A browser SPA has config compiled into its bundle, so the environment
is chosen at *build* time. That is not a tooling flaw — it follows from where the code runs — and it is
why the frontend Dockerfile takes `ARG`s and the backends do not.

**Check what your health check actually checks.** Spring Boot's default readiness group contains only
`readinessState` and reflects no dependency at all. A readiness probe against it passes for a pod that
cannot reach its database. Phase 8 found this by *listing the registered indicators live* rather than
assuming, and discovered along the way that Spring Kafka registers no broker indicator — so the
obvious fix would have silently included nothing.

---

## Build it yourself

**Containers** — [section 1](1-containers-and-compose.md)

1. A multi-stage Dockerfile per backend: `maven:3.9-eclipse-temurin-21` building with
   `-pl <common>,<service> -am package -DskipTests`, then `eclipse-temurin:21-jre-alpine` with just the
   jar. **Build context is the repo root** — Maven's reactor needs every listed module on disk. Delete
   `*.jar.original` before the `COPY` glob.
2. A `.dockerignore` covering `target/`, `node_modules/`, `.git/`.
3. A frontend Dockerfile: `node:22-alpine` building, `nginx:alpine` serving, with a `VITE_*_SERVICE_URL`
   **`ARG`** per backend defaulting to `http://localhost:808X`. Dependencies before source.
4. An `nginx.conf` with `try_files $uri $uri/ /index.html` — without it, deep links 404.
5. `docker-compose.yml`: PostgreSQL with a `pg_isready` health check; Kafka in KRaft with **two
   listeners** (`HOST://localhost:9092` for the host, `INTERNAL://kafka:29092` for containers); five
   backends with `SPRING_DATASOURCE_URL` and `KAFKA_BOOTSTRAP_SERVERS`, `depends_on … condition:
   service_healthy`, and a `wget --spider` health check against `/actuator/health`; the frontend on
   5173.

**Kubernetes** — [section 2](2-kubernetes-manifests.md)

6. Numbered manifests so `kubectl apply -f <dir>` orders them: namespace, secrets, Postgres, Kafka,
   five services, frontend.
7. **Do not commit a real password.** Create the Secret imperatively, or template it from the
   environment.
8. PostgreSQL: PVC + Deployment with `strategy: Recreate` and `subPath: pgdata`, plus a `pg_isready`
   exec readiness probe. Not a StatefulSet, and be able to say why.
9. Per service: a ConfigMap with the same two variables Compose uses, a Deployment with `envFrom` +
   `secretKeyRef`, and a `NodePort` Service on a fixed `3008X`.

**Probes and resources** — [section 3](3-probes-and-resources.md)

10. `management.endpoint.health.probes.enabled: true`, and probes targeting
    `/actuator/health/readiness` and `/actuator/health/liveness`.
11. **Liveness slower and later than readiness** — 45s/15s against 30s/5s.
12. **Check which health indicators exist**, live, before writing a readiness group. Then set
    `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE: "readinessState,db"` in the ConfigMap — a
    manifest-level override, not a source change.
13. Requests and limits per service (150m/320Mi requested, 500m/640Mi capped) and sum the requests
    against your node's *allocatable* capacity.
14. `kind-config.yaml` mapping each NodePort to the **same host port Compose uses**, so the frontend
    image works unchanged.
15. `kind create cluster`, `docker compose build`, `kind load docker-image … ×6`, `kubectl apply -f`.

**Demonstrate it** — the part that makes probes more than configuration

16. Delete the Postgres pod. Watch the backends leave the Service endpoints and **not restart**. Bring
    it back; watch them return.
17. `kubectl scale deployment inventory-service --replicas=3` and watch the consumer group rebalance
    across three partitions.
18. Delete a consumer pod mid-workflow and watch redelivery plus the idempotency ledger suppressing the
    duplicate.

**Done when:** `docker compose up --build` starts everything from a fresh clone; the same images run on
kind and the browser cannot tell which; a Postgres outage fails readiness without triggering a single
restart; and every scenario from [Chapter 5](../05-scenarios-and-frontend/README.md) still passes
against the cluster.

---

## Next

[Section 1 — Containers and Compose](1-containers-and-compose.md).
