# 7.3 — Probes and resources

[← Kubernetes manifests](2-kubernetes-manifests.md) · [Chapter 7 ↑](README.md)

The part of Phase 8 that ADR-007 singled out as worth getting right, and the part most likely to come
up in conversation.

---

## Why ADR-007 flagged this specifically

> Readiness and liveness are treated as **genuinely different questions** when they are written [...]:
> a broker that is temporarily unreachable should fail readiness, not liveness, because restarting a
> healthy pod does not fix a dependency — **and being able to explain that distinction is part of what
> the project is for.**

An ADR naming a concept as something the project exists to demonstrate. The distinction is also the
one most commonly got wrong in real deployments, with a memorable failure mode.

> **Primer — [Kubernetes: health probes](../technology/kubernetes/probes.md)**
> The three probes and the question each answers, why a dependency check in liveness causes a restart
> storm, every timing field, probe types and their costs, startup probes, Spring Boot's health groups,
> and how probes amplify load-induced failure.

---

## The configuration

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 6

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 45
  periodSeconds: 15
  timeoutSeconds: 3
  failureThreshold: 6
```

**Liveness is slower and later in every dimension** — a 45-second initial delay against readiness's 30,
and a 15-second period against 5.

That asymmetry is deliberate and follows from the costs. Readiness failing is cheap: a pod briefly
leaves the load balancer, and comes back within seconds. Liveness failing is expensive: a restart, a
lost in-flight request, a cold JVM, and a fresh startup delay. **The expensive action should require
more evidence and more time.**

A liveness probe firing before the JVM has finished starting is the classic self-inflicted crash loop.
45 seconds plus 6 failures at 15-second periods means roughly two minutes before Kubernetes concludes
the process is unrecoverable.

---

## The finding: readiness that reflected nothing

This is the most instructive thing in Phase 8, and it came from checking rather than assuming.

```yaml
# Phase 8 finding: this app has no Kafka Actuator health indicator registered (verified live —
# /actuator/health's component list is only db/diskSpace/livenessState/ping/readinessState/ssl,
# no "kafka" entry), so Spring Boot's default readiness group (readinessState only) never
# reflects a broker outage. Wiring the one dependency indicator that IS registered (db) into the
# readiness group is what makes the readiness-vs-liveness distinction ADR-007 requires actually
# observable: a Postgres outage now fails readiness (pod pulled from Service endpoints) without
# touching liveness (no restart) — demonstrated live, see docs/agent-reports/phase-8-kubernetes.md.
# This is a K8s-manifest-level property override (Spring's relaxed env-var binding), not an
# application source change.
MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE: "readinessState,db"
```

Four things here, each worth separating.

**The default readiness group is nearly empty.** Spring Boot's `readiness` group contains only
`readinessState` — the application context's own lifecycle. It says "Spring finished starting." It
says **nothing about whether any dependency is reachable.**

A readiness probe against the default group therefore passes for a pod that cannot reach its database
or its broker. Traffic keeps arriving. The probe is decorative.

**The available indicators were checked, not assumed.** *"verified live — `/actuator/health`'s
component list is only `db`/`diskSpace`/`livenessState`/`ping`/`readinessState`/`ssl`, no `kafka`
entry."*

Spring Kafka registers **no broker health indicator by default**. Writing
`include: readinessState,db,kafka` would have looked correct and silently included nothing.

**The fix went in the manifest, not the source.** Spring's relaxed binding maps
`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE` onto
`management.endpoint.health.group.readiness.include`, so a deployment-specific health policy stays in
the deployment. The application image is unchanged, and Compose is free to have a different policy.

**It was demonstrated, not asserted.** *"a Postgres outage now fails readiness (pod pulled from Service
endpoints) without touching liveness (no restart) — demonstrated live."* Delete the Postgres pod, watch
the backends leave the Service endpoints, watch them not restart, bring Postgres back, watch them
return.

That is the difference between having configured probes and having *demonstrated* the distinction —
which is what ADR-007 asked for.

> **Open question — the Kafka half is still unreflected.** Readiness now covers the database. A Kafka
> outage still fails nothing, because there is no indicator to include. A custom `HealthIndicator`
> calling `AdminClient.describeCluster()` would close it, and the repo does not record whether that
> was considered and rejected as scope or simply not reached. Worth knowing, since "what happens if
> Kafka goes down?" is a natural follow-up.

---

## Resources

```yaml
resources:
  requests:
    cpu: 150m
    memory: 320Mi
  limits:
    cpu: 500m
    memory: 640Mi
```

Per backend service: 0.15 cores and 320MiB reserved, capped at 0.5 cores and 640MiB.

**Requests are scheduling; limits are runtime**, and the two resources behave completely differently:

- **CPU is compressible.** Over the limit, the container is *throttled* — slower, not killed. A CPU
  limit set too low shows up as latency, which is much harder to attribute than a crash.
- **Memory is not.** Over the limit, the container is **OOM-killed** and restarted. No degraded mode.

For a JVM, that asymmetry is the whole story. Modern JVMs read the container memory limit and size the
heap from it — but heap is not the only thing a JVM allocates. Metaspace, thread stacks, code cache,
and direct byte buffers sit on top, and the JVM's default heap fraction leaves room for them only
approximately.

> **Not yet.** These manifests do **not** cap the JVM heap explicitly. On a laptop with headroom that
> is fine. On [Chapter 9](../09-production/README.md)'s 2-vCPU / 4GB production box it was blocking
> work item T2 — *"cap JVM heaps explicitly rather than relying on defaults"* — because five JVMs each
> sizing their own heap from their own limit is how you exhaust a small node.

**Requests × 8 pods is the real floor.** Five backends plus the frontend plus PostgreSQL plus Kafka is
what has to fit in a node's *allocatable* capacity — which is less than its total, because the kubelet
and system daemons reserve some. Sprint 2's sizing decision cites this project's own Phase 10
measurements: the 8-pod baseline stack runs inside 3.825GiB.

---

## Running it on kind

```yaml
# Maps container NodePorts to the same host ports Docker Compose already uses (8081-8085 for the
# 5 backend services, 5173 for the frontend), per docs/adr/ADR-007. Point of doing it this way:
# the frontend's Vite build already bakes in http://localhost:8081..8085 as its default backend
# URLs, so keeping those exact host ports means the existing frontend Docker image (built once by
# Phase 7, unmodified here) works against the kind cluster with zero rebuild and zero env changes —
# the browser can't tell it isn't talking to Compose.
extraPortMappings:
  - containerPort: 30081
    hostPort: 8081
```

**kind** runs a Kubernetes cluster inside Docker containers — a node is a container. `extraPortMappings`
publishes a container port to the host, which is how a NodePort becomes reachable from the browser.

The clever part is choosing to map `30081 → 8081`. The frontend image has `http://localhost:8081` baked
into its bundle ([section 1](1-containers-and-compose.md)), so preserving the host ports means **the
Phase 7 image works unmodified against Kubernetes.** The browser genuinely cannot tell which
orchestrator is behind it.

That is a small decision that removes a whole category of work — a second frontend build, a second set
of environment variables, and a second thing to keep in sync.

The workflow:

```bash
kind create cluster --config infrastructure/kind-config.yaml
docker compose build
kind load docker-image order-service:local --name orderfulfillment   # ×6
kubectl apply -f infrastructure/kubernetes/
```

`kind load` is the step people miss. There is no registry, so images must be **loaded into the node
container** explicitly. Skip it and you get `ImagePullBackOff` while the cluster tries Docker Hub.

ADR-007's reason for kind over Minikube: *"a `kind` cluster is free, starts in a minute, can be
recreated identically inside CI, and demonstrates the same Kubernetes objects."* The CI point is the
strongest — kind is designed to run inside a GitHub Actions runner.

---

## What running on Kubernetes actually demonstrates

ADR-007 was explicit that Kubernetes had to earn its place:

> Kubernetes gets introduced for reasons that can be defended in an interview — replicas, restart
> behavior, scaling — rather than as a checkbox.

Concretely, three things Compose cannot show:

**Multiple consumer replicas in one consumer group.** `kubectl scale deployment inventory-service
--replicas=3` and watch three pods share three partitions.
[Chapter 8](../08-observability-and-scaling/README.md).

**Pod restarts as a way to trigger consumer recovery.** Delete a pod and watch a rebalance, a
redelivery, and the idempotency ledger suppressing the duplicate — Scenario 5's mechanism, driven by
the platform instead of by a demo endpoint.

**Readiness gating during a rolling update.** New pods do not receive traffic until they are ready, so
a deploy does not drop requests. Which is also where a real problem surfaces:

> Deployment problems surface late, and some are only visible in a cluster: readiness gating during
> rolling updates, resource limits triggering OOM kills, and **SSE connections dropping when a pod is
> replaced**.

All three happened. Two of them are in [Chapter 9](../09-production/README.md).

---

[← Kubernetes manifests](2-kubernetes-manifests.md) · [Chapter 7 ↑](README.md)
