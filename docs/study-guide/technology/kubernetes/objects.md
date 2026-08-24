# Kubernetes: the object model

*Referenced from [Chapter 7.2 — Kubernetes manifests](../../07-containers-and-kubernetes/2-kubernetes-manifests.md).*

---

## The mental model

Kubernetes is a **reconciliation loop**. You declare the state you want; controllers continuously
compare that against reality and act to close the gap.

You never say "start a container." You say "there should be three of these," and something makes it so
— and keeps making it so when a node dies, a process crashes, or someone deletes a pod by hand.

Everything follows from that. `kubectl apply` records intent. Whether reality matches yet is a separate
question, which is what `kubectl get` and `kubectl describe` answer.

## The objects you actually need

### Pod

One or more containers that share a network namespace and can share volumes. Containers in a pod reach
each other on `localhost` and are always scheduled together.

**You rarely create pods directly.** A bare pod that dies stays dead. It is the unit of scheduling, not
the unit you manage.

### Deployment

Declares "N replicas of this pod template," and owns the rollout logic for changing it.

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 1
  selector:
    matchLabels:
      app: order-service        # must match the template's labels
  template:
    metadata:
      labels:
        app: order-service
    spec:
      containers:
        - name: order-service
          image: order-service:local
```

Change the template and the Deployment performs a **rolling update**: new pods up, old pods down,
governed by `maxSurge` (how many extra may exist) and `maxUnavailable` (how many may be missing).

The `selector` matching the template's labels is not redundancy — the selector is how the Deployment
finds pods it owns, and a mismatch is rejected.

**Other workload types**, briefly: `StatefulSet` for stable identities and per-replica storage;
`DaemonSet` for one pod per node; `Job`/`CronJob` for run-to-completion work.

### Service

A stable name and virtual IP in front of a changing set of pods. Pods are ephemeral and get new IPs;
a Service does not.

```yaml
apiVersion: v1
kind: Service
spec:
  selector:
    app: order-service        # any pod with this label
  ports:
    - port: 8081
      targetPort: 8081
```

The selector is evaluated **continuously**. A pod that becomes ready is added to the endpoint list; one
that fails readiness is removed. That is the mechanism behind zero-downtime rollouts.

Within the cluster, `http://order-service:8081` resolves via DNS from any namespace-local pod.

**Types:**

| Type | Reachable from | Use |
|---|---|---|
| `ClusterIP` (default) | Inside the cluster only | Service-to-service |
| `NodePort` | Every node, on a high port (30000–32767) | Local development, simple demos |
| `LoadBalancer` | Externally, via a cloud load balancer | Cloud production |
| `Ingress` (separate object) | Externally, HTTP-aware routing | Anything needing paths, hosts, TLS |

`NodePort` is the blunt one — it opens the same port on **every** node, which is a real security
consideration if those nodes are internet-facing.

### ConfigMap and Secret

Non-secret and secret key/value configuration, injected as environment variables or files.

```yaml
envFrom:
  - configMapRef:
      name: order-service-config
env:
  - name: SPRING_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: postgres-credentials
        key: POSTGRES_PASSWORD
```

**Secrets are base64-encoded, not encrypted.** `echo <value> | base64 -d` is the entire attack. They
are separate from ConfigMaps so that access can be restricted by RBAC and so they are not printed by
casual `kubectl get -o yaml` habits — not because the encoding protects anything.

**A committed Secret manifest is a committed credential.** Real options: create it imperatively
(`kubectl create secret`) outside version control, use an external store (Vault, cloud secret
managers, External Secrets Operator), or encrypt it in git with SOPS or Sealed Secrets.

**Changing a ConfigMap does not restart pods.** Environment variables are read at container start.
Either roll the Deployment explicitly, or mount the ConfigMap as a volume (which does update, on a
delay) and have the application watch the file.

### Namespace

A scope for names and a boundary for quotas and RBAC. Not a security boundary by itself — pods in
different namespaces can still reach each other unless a `NetworkPolicy` says otherwise.

## Resource requests and limits

```yaml
resources:
  requests:
    cpu: 150m           # 0.15 of a core
    memory: 320Mi
  limits:
    cpu: 500m
    memory: 640Mi
```

**Requests are for scheduling.** The scheduler places a pod on a node with that much *unallocated*
request. It is a reservation, not a measurement.

**Limits are enforced at runtime**, and the two resources behave completely differently:

- **CPU is compressible.** Exceed the limit and you are *throttled* — slowed, not killed. A CPU limit
  that is too low shows up as latency, not failure, which makes it hard to spot.
- **Memory is not.** Exceed the limit and the container is **OOM-killed** and restarted. There is no
  degraded mode.

That asymmetry is the single most useful thing to know here. It is also why a JVM in a container needs
its heap capped explicitly relative to the memory limit — a JVM that sizes its heap from the container
limit and then also allocates metaspace, thread stacks, and direct buffers on top will exceed it.

**Requests without limits** is a legitimate strategy for latency-sensitive workloads: guaranteed a
share, free to burst. **Limits without requests** is almost always wrong.

## Reading the state

```bash
kubectl get pods -n <namespace>            # is it running and ready?
kubectl describe pod <name>                # events: why it isn't
kubectl logs <name> [-f] [--previous]      # --previous = the container before the last restart
kubectl get events --sort-by=.lastTimestamp
```

`kubectl describe` is the one to reach for first. The **Events** section at the bottom is where the
scheduler, kubelet, and controllers explain themselves — failed image pulls, probe failures,
insufficient resources, OOM kills.

`--previous` on logs is what you need after a `CrashLoopBackOff`: the current container has barely
started, and the one that died holds the answer.
