# 7.2 — Kubernetes manifests

[← Containers and Compose](1-containers-and-compose.md) · [Next: Probes and resources →](3-probes-and-resources.md)

Twelve YAML files, numbered so `kubectl apply -f infrastructure/kubernetes/` applies them in order.
No Helm, no operators, no templating.

---

## Plain YAML, on purpose

ADR-007 rejected Helm for Phase 8:

> Templating would remove per-service duplication. Rejected for Phase 8 as premature: **plain YAML is
> what a reviewer can read without knowing Helm**, and five nearly identical Deployments are not yet a
> duplication problem worth a templating layer.

Worth defending, because "why no Helm?" is a certain question. Five Deployments differing in name,
image, and port are five files a reader can diff by eye. A chart is a second language between the
reader and the manifests, and the abstraction pays for itself somewhere north of "a handful of nearly
identical services."

[Chapter 9](../09-production/README.md) does eventually add a templating layer — **kustomize
overlays**, not Helm, and only when a genuine second environment appeared. That is the trigger to wait
for.

> **Primer — [Kubernetes: the object model](../technology/kubernetes/objects.md)**
> Reconciliation, Pods and Deployments, Services and their types, ConfigMaps and Secrets and what
> Secrets actually protect, namespaces, resource requests vs. limits, and reading cluster state.

---

## The file layout

```
00-namespace.yaml           orderfulfillment
01-secrets.yaml             postgres-credentials
02-postgres.yaml            PVC + Deployment + Service
03-kafka.yaml               Deployment + Service (KRaft)
04-order-service.yaml       ConfigMap + Deployment + Service
05-inventory-service.yaml   …the same shape ×4
09-frontend.yaml
10-inventory-service-hpa.yaml   (Sprint 2)
11-metrics-server.yaml          (Sprint 2)
```

Numeric prefixes because `kubectl apply -f <dir>` processes files in lexical order, and dependencies
run one way: namespace before anything in it, secrets before the pods that mount them.

**One file per service, holding all three objects** — ConfigMap, Deployment, Service — separated by
`---`. Everything about `order-service` is in `04-order-service.yaml`, which is the layout that reads
best when you are trying to understand one service rather than one object type.

---

## A service, end to end

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-config
  namespace: orderfulfillment
data:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orderfulfillment
  KAFKA_BOOTSTRAP_SERVERS: kafka:29092
---
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: order-service
          image: order-service:local
          imagePullPolicy: IfNotPresent
          envFrom:
            - configMapRef:
                name: order-service-config
          env:
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: POSTGRES_PASSWORD
---
apiVersion: v1
kind: Service
spec:
  selector:
    app: order-service
  ports:
    - port: 8081
      targetPort: 8081
      nodePort: 30081
  type: NodePort
```

### The same environment variables as Compose

`SPRING_DATASOURCE_URL` and `KAFKA_BOOTSTRAP_SERVERS`, with `postgres` and `kafka` resolving through
cluster DNS instead of Compose DNS. **The application does not know which orchestrator it is running
under**, and never had to.

That is the whole return on the `${VAR:default}` discipline: two orchestrators, one image, zero
application changes.

### Configuration split by sensitivity

Non-secret values in a ConfigMap, injected wholesale with `envFrom`. Credentials in a Secret, injected
key by key with `secretKeyRef`.

The split is not about the injection mechanism — both end up as environment variables. It is about
**what can be read by whom**: Secrets can be restricted with RBAC and are excluded from the casual
`kubectl get -o yaml` habits that print ConfigMaps.

> **We got this wrong.** `01-secrets.yaml` originally contained a real, committed PostgreSQL password.
> A Secret manifest in git is a credential in git — base64 is an encoding, not encryption. Sprint 2's
> security pass caught it, and [Chapter 9](../09-production/README.md) replaces it with a
> `create-postgres-secret.sh` that generates the Secret imperatively, outside version control.
> [Chapter 10](../10-retrospective/README.md).

### `imagePullPolicy: IfNotPresent`

With no registry in Phase 8, images are built locally and loaded into the cluster with
`kind load docker-image`. The default policy for a tag other than `latest` would be `IfNotPresent`
anyway, but stating it prevents the cluster trying to pull `order-service:local` from Docker Hub and
failing with `ImagePullBackOff` — the single most common first-time kind error.

### `NodePort`, with fixed ports

```yaml
type: NodePort
ports:
  - port: 8081
    targetPort: 8081
    nodePort: 30081
```

`NodePort` rather than `ClusterIP` because the browser must reach these services from outside the
cluster and there is no ingress controller in Phase 8. Fixed node ports rather than
auto-assigned, so they can be mapped predictably — see [section 3](3-probes-and-resources.md).

> **This is exactly what has to change in production.** `NodePort` opens the port on **every node**.
> On an internet-facing box that means the `/demo/consumers` endpoints — the ones that pause consumers
> — are reachable by anyone who scans ports 30000–32767, regardless of any ingress rules.
> [Chapter 9](../09-production/README.md) closes the range at the firewall and routes everything
> through a single ingress instead.

---

## Stateful infrastructure

### PostgreSQL, and why not a StatefulSet

```yaml
# Single-instance Postgres in-cluster (plain Deployment + PVC, not a StatefulSet — this is a
# local demo cluster with one replica and no replication story, matching docker-compose.yml's
# postgres service; a StatefulSet would demonstrate nothing extra here).
```

The reflexive answer for a database is `StatefulSet`, and the comment declines it with a reason: what
a StatefulSet provides — stable network identity per replica, per-replica volumes, ordered rollout —
is only meaningful with **more than one replica**. With one instance and no replication, it is
ceremony.

Being able to say *what a StatefulSet would buy and why it does not apply here* is a better answer than
having used one.

Two details that do matter at one replica:

```yaml
strategy:
  type: Recreate # single PVC, ReadWriteOnce — never run two postgres pods at once
```

The default `RollingUpdate` starts the new pod before terminating the old one. With a
`ReadWriteOnce` volume the new pod cannot mount it, so the rollout hangs — and if it *could*, two
PostgreSQL processes on one data directory would corrupt it. `Recreate` takes the old pod down first,
accepting downtime as the correct trade.

```yaml
volumeMounts:
  - name: postgres-data
    mountPath: /var/lib/postgresql/data
    subPath: pgdata # avoid postgres complaining about lost+found in the mount root
```

A mounted volume's root often contains `lost+found`, and PostgreSQL refuses to initialize a data
directory that is not empty. `subPath` puts the data one level down. A one-line fix for an error
message that is otherwise thoroughly confusing.

### Kafka

Same shape — a single-node KRaft broker with the same dual-listener arrangement as Compose, with
`kafka:29092` for in-cluster clients.

Note what is **not** here: no replication, no multiple brokers, no rack awareness. Single-node with
`replication.factor=1`, matching
[Chapter 3](../03-kafka-and-services/1-events-on-the-wire.md)'s topic configuration. The project runs
Kubernetes to demonstrate **application** scaling and restart behavior, not to operate Kafka — and
`project-overview.md` rules "full production Kafka operations" out explicitly.

---

## What Phase 8 did *not* add

ADR-007's deferral list is worth reading as a statement of scope:

> **Deferred past Phase 8:** HorizontalPodAutoscaler, PodDisruptionBudget, NetworkPolicy, and service
> mesh — the last being an explicit non-goal.

- **HPA** — arrived in Sprint 2, after Phase 10 produced measurements to base it on.
  [Chapter 8](../08-observability-and-scaling/README.md).
- **PodDisruptionBudget** — meaningful with multiple replicas and voluntary disruptions. Neither
  applies to a one-replica local cluster.
- **NetworkPolicy** — would be the right answer to the `NodePort` exposure above. Rejected here and
  handled at the firewall in [Chapter 9](../09-production/README.md).
- **Service mesh** — an explicit non-goal in `project-overview.md`.

Each is deferred with a reason and a trigger, rather than listed as future work.

---

[← Containers and Compose](1-containers-and-compose.md) · [Next: Probes and resources →](3-probes-and-resources.md)
