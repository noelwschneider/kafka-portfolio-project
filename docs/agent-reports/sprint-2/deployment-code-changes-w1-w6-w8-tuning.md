# Deployment Readiness — Code Changes W1, W6, W8 and the CX23 Tuning (T1–T4)

Completes Sprint 2 goal 4's repo-side work. W2–W5 landed earlier
(`deployment-code-changes-w2-w5.md`), W7 in `security-hygiene-pass.md`. What remained — the ingress
allowlist, image building, the 2-vCPU tuning, ADR-010 and the docs — is done and verified against a
real local `kind` cluster.

---

## W1 — Ingress with per-service path prefixes and an allowlist

### Approach chosen: Traefik `Ingress` + `StripPrefix` middleware

Not the `server.servlet.context-path` fallback. Reasons, in order of weight:

1. It leaves the applications completely untouched. The context-path approach would move every
   service's actuator paths, which means the Deployments' probe paths move too — a change to the
   base manifests in service of a production-only concern, and a new way for local and deployed
   behavior to diverge.
2. The allowlist stays in one file that reads as an allowlist. With context paths, "what is
   reachable" would be spread across five `application.yml` files plus an Ingress.
3. k3s ships Traefik, so the middleware costs nothing to install on the box.

It proved straightforward — no awkwardness that would have justified the fallback.

### What was added

Everything production-only lives in `infrastructure/kubernetes/production/`, alongside W7's script.
The base manifests are unchanged except for T1 (below), and `kubectl apply -f
infrastructure/kubernetes/` behaves exactly as it did.

| File | Purpose |
| --- | --- |
| `production/common/kustomization.yaml` | The overlay: base manifests minus `01-secrets.yaml`, six Services patched to `ClusterIP`, plus the two files below |
| `production/common/ingress.yaml` | `Middleware` (StripPrefix over the five `/svc/*` prefixes) + two `Ingress` objects |
| `production/common/patch-tuning.yaml` | T2/T3 — heap caps, `startupProbe`s, relaxed probe timings, `SPRING_PROFILES_ACTIVE=production` |
| `production/ghcr/kustomization.yaml` | The overlay as deployed: `common/` + GHCR image references (**has an `OWNER` placeholder**) |
| `production/local-verify/kustomization.yaml` | The overlay against locally-built images — the harness the acceptance criteria below were checked with, committed so the check is repeatable |
| `production/render.sh` | Renders any of the above to stdout for piping into `kubectl apply -f -` |

Two design points worth surfacing:

**The frontend is enumerated, not a catch-all.** A `/` prefix rule would match everything, including
`/svc/inventory/demo/consumers/...`, and would answer it with the SPA's `index.html` — a 200, not a
404. Nothing would leak (that request still never reaches a backend), but "not listed means 404" is
a far easier property to check than "not listed means you get some HTML". So the nine client-side
routes from `App.tsx` plus `/assets`, `/index.html` and `/favicon.svg` are listed explicitly. The
cost, documented in the file's header: adding a frontend route means adding it here, or it 404s in
production while working locally.

**`render.sh` exists because of a kustomize load restriction, not by preference.** The overlay's
`resources:` reach up into the base manifests, and kustomize refuses to load files outside its root
unless the load restrictor is off — a flag `kubectl kustomize` takes and `kubectl apply -k` does
not. The alternative, a `kustomization.yaml` inside `infrastructure/kubernetes/`, would break
`kubectl apply -f infrastructure/kubernetes/` for the local flow. Keeping the base directory pure
Kubernetes objects was treated as the constraint.

### Verification — real output

Local `kind` cluster, Traefik v3.3 installed from upstream CRDs/RBAC plus a small Deployment
exposing entrypoint `:8000` on nodePort 30173 (which `kind-config.yaml` already maps to host 5173).
Images built locally, including `frontend:local-prod` with the W2 production build args.

**Base manifests alone still produce today's NodePort behavior** (checked first, on a clean
cluster, before any overlay existed):

```
8081: 200   8082: 200   8083: 200   8084: 200   8085: 200   frontend 5173: 200
POST /demo/consumers/order-created/pause via NodePort 8082 : 200
GET  /actuator/prometheus via NodePort 8081                : 200
```

That last pair is the pre-change exposure, recorded deliberately as the "before".

**With the production overlay applied** — allowlisted paths (all `200`):

```
GET /  /index.html  /orders  /orders/abc-123  /scenarios  /scenario-runs/xyz
GET /events  /health  /architecture
GET /svc/order/api/orders                 GET /svc/order/actuator/health
GET /svc/inventory/api/inventory          GET /svc/inventory/actuator/health
GET /svc/payment/actuator/health          GET /svc/fulfillment/actuator/health
GET /svc/scenario/demo/scenarios          GET /svc/scenario/demo/scenario-runs
GET /svc/scenario/demo/events             GET /svc/scenario/actuator/health
```

Blocked paths (all `404` from Traefik, no backend involved):

```
POST /svc/inventory/demo/consumers/order-created/pause      404
GET  /svc/inventory/demo/consumers                          404
POST /svc/fulfillment/demo/consumers/order-fulfilled/pause  404
PUT  /svc/payment/demo/payment-behavior                     404
GET  /svc/order/actuator/prometheus                         404
GET  /svc/order/actuator/metrics                            404
GET  /svc/scenario/actuator/prometheus                      404
GET  /svc/inventory/actuator/prometheus                     404
GET  /svc/order/api/../demo/consumers                       404
GET  /nope                                                  404
```

**NodePorts are gone**, from the host and from inside the node:

```
$ kubectl get svc -n orderfulfillment -o custom-columns=NAME:.metadata.name,TYPE:.spec.type,NODEPORT:.spec.ports[0].nodePort
frontend              ClusterIP   <none>
fulfillment-service   ClusterIP   <none>
inventory-service     ClusterIP   <none>
kafka                 ClusterIP   <none>
order-service         ClusterIP   <none>
payment-service       ClusterIP   <none>
postgres              ClusterIP   <none>
scenario-service      ClusterIP   <none>

$ curl -m 4 http://localhost:8081/actuator/health          -> 000 (unreachable)
$ docker exec …control-plane curl -m 4 localhost:30081/…   -> 000 (unreachable)
```

**The system actually works through the ingress**, which is the part a routing test alone would
miss. Three scenarios driven end to end through `/svc/scenario`:

```
standard-order  -> COMPLETED (run-101)
consumer-outage -> COMPLETED (run-102)
payment-failure -> COMPLETED (run-103)
```

`consumer-outage` is the important one: it proves Scenario Service's **server-side** call to
`POST /demo/consumers/{name}/pause` still works over cluster-internal DNS while the same path 404s
from outside. That is the whole W1 thesis, demonstrated rather than argued.

**Both SSE streams survive the ingress** — events arrived promptly, no proxy buffering, on
`/svc/scenario/demo/scenario-runs/{id}/stream` (`timeline-entry` events) and
`/svc/order/api/orders/stream` (`order-status-changed` events). Raw event frames were observed on
both.

**The served frontend bundle is same-origin**: the asset served through the ingress contains all
five `/svc/*` prefixes and **zero** occurrences of `localhost:808`.

One honest caveat, documented in `ingress.yaml` and the production README: Traefik implements
`pathType: Prefix` as a literal string prefix rather than a path-segment match. So
`/svc/scenario/demo-secret` matches the `/svc/scenario/demo` rule, reaches scenario-service and gets
that service's own error response (a 500 — see "needs a decision" below). It can only ever reach the
service the prefix already routes to, never a different one, and no allowlist-excluded path is a
string extension of an allowlisted one — checked explicitly for the wedge paths.

---

## W6 — Container images the box can actually run

`.github/workflows/build-images.yml` builds all six images for `linux/amd64` on GitHub's native x86
runners and pushes to GHCR, with a matrix over the five services plus the frontend (the only one
with build args — the `/svc/*` base URLs from W2).

**Trigger: `workflow_dispatch` only.** Reasoning is in the file's header: deployment is manual
anyway, and a build firing on every push would produce images nobody asked for and move the mutable
`latest` tag under a box whose job is to be boringly stable. Each run also pushes an immutable
commit-SHA tag, which is what to pin in `ghcr/kustomization.yaml` after the first successful deploy.

The manual `docker buildx --platform linux/amd64 --push` fallback is documented in the same header,
including the frontend's five build args, since CI is new and may not be trusted on the first real
deploy.

**Not verified by execution**: the repo has no `origin` remote yet, so the workflow has never run.
YAML validity and matrix structure were checked (`yaml.safe_load`), and the Dockerfiles/contexts
match what `README.md`'s `kind` section already builds successfully. See "needs a decision" below.

---

## T1 — Kafka readiness probe (base manifests, not just production)

`infrastructure/kubernetes/03-kafka.yaml`: the `exec` of `kafka-broker-api-versions.sh` — which
starts a second JVM inside the broker container on every check — is now a `tcpSocket` check on the
INTERNAL listener (29092), the port the five backend Deployments actually connect to.

This went into the **base** manifests because it is a strict improvement locally too, and a
controlled A/B on the same cluster showed that is not a theoretical claim.

**With the original exec probe** (base manifests from `HEAD`, applied to a cluster already running
Traefik and therefore under some load):

```
Warning  Unhealthy  2m43s (x55 over 7m48s)  kubelet
  Readiness probe failed: command timed out:
  "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092" timed out after 5s

NAME                  READY   RESTARTS
kafka-6cf54d4f55…     false   0          <- never became Ready in ~8 minutes
fulfillment-service…  false   3
inventory-service…    false   3
order-service…        false   2
payment-service…      false   3
scenario-service…     false   2
```

Kafka never reached Ready, so its Service had no endpoints, so every service crash-looped
(`Rebootstrapping with Cluster(id = null …)` in the logs).

**Applying only the new `03-kafka.yaml` to that same cluster**, changing nothing else:

```
Readiness:  tcp-socket :29092 delay=15s timeout=5s period=5s #success=1 #failure=6
Warning  Unhealthy  (x1)  Readiness probe failed: dial tcp …:29092: connect: connection refused

NAME                  READY   RESTARTS
kafka-5cc856b8c4…     true    0          <- Ready within ~15-20s, one boot-time refusal
```

and every service then converged to Ready without further intervention. This is the Phase 10
flapping reproduced and then fixed, on the development machine, before the box exists.

The cost is stated honestly in the manifest comment: a TCP accept proves the listener is bound, not
that the broker will answer a metadata request. That is a weaker signal — bought by removing the
probe's own CPU cost, which was the failure mode it was supposed to detect.

---

## T2 — Explicit heap caps (production overlay)

`-XX:MaxRAMPercentage=60.0` via `JAVA_TOOL_OPTIONS` on the five services; `KAFKA_HEAP_OPTS=-Xmx512m
-Xms512m` on the broker. 60% of the 640 MiB limit is 384 MiB of heap, leaving ~256 MiB for
metaspace, code cache, thread stacks and direct buffers — the part that gets a container OOMKilled
when people set the heap equal to the limit. Verified inside the running pods:

```
Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=60.0
size_t MaxHeapSize        = 402653184        {product} {ergonomic}     # 384 MiB, was 160 MiB by default
double MaxRAMPercentage   = 60.000000        {product} {environment}

$ kubectl exec deploy/kafka -- ps -o args= -p 1 | grep -E '^-Xm'
-Xmx512m
-Xms512m
```

---

## T3 — Probe timings (production overlay)

A `startupProbe` on all five services and Kafka (up to 5 minutes: `initialDelaySeconds` 15–20,
`periodSeconds` 10, `failureThreshold` 30), plus longer `periodSeconds`/`timeoutSeconds` on the
liveness and readiness probes. The `startupProbe` is the right tool rather than simply inflating
`initialDelaySeconds`: while it runs, liveness and readiness are not evaluated at all, so a slow
cold start cannot restart a pod that is merely waiting for a CPU slice.

Observed effect on the same machine: the base run had 2 of 8 pods restart once during startup
(`Exit Code: 1` about 16 s in, before Kafka was serving); the overlay run reached all-Ready with
**zero restarts across all 8 pods**. Verified live:

```
$ kubectl get pod -l app=order-service -o jsonpath='{…startupProbe}'
{"failureThreshold":30,"httpGet":{"path":"/actuator/health/liveness","port":8081},
 "initialDelaySeconds":20,"periodSeconds":10,"successThreshold":1,"timeoutSeconds":5}
```

---

## T4 — Scenario timeouts (production Spring profile only)

`services/scenario-service/src/main/resources/application-production.yml` now widens three timeouts
3× — roughly the ratio of available cores between the 8-vCPU development machine they were tuned on
and the CX23:

| Property | Default | Production |
| --- | --- | --- |
| `order-poll-timeout-ms` | 20 000 | 60 000 |
| `high-volume-lag-poll-timeout-ms` | 60 000 | 180 000 |
| `high-volume-order-watch-timeout-ms` | 60 000 | 180 000 |

Burst size (60) and submission concurrency (20) are deliberately unchanged — these widen how long
the scenario waits, not what it does. `SPRING_PROFILES_ACTIVE=production` is set on the
scenario-service Deployment by the overlay and nowhere else, which also closes the loose end W2–W5
flagged: W4's idle auto-reset now has something that actually turns it on.

The instruction was to widen only after confirming the work genuinely completes. The high-volume
scenario was run through the ingress with the production profile active:

```
high-volume -> COMPLETED in 27s
timeline entries: 505
  60 x POST /api/orders      60 x OrderCreated        60 x InventoryReserved
  60 x PaymentRequested      60 x PaymentAuthorized   60 x ShipmentCreated
  60 x Order FULFILLED
summary: {"ordersSubmitted": 60, "ordersFulfilled": 60, "ordersNotFulfilled": 0,
          "drainDurationMs": 22062, "endToEndDurationMs": 25533}
```

60 submitted, 60 fulfilled, 0 not fulfilled — genuinely completing, not a timeout being outrun. On
this hardware the drain took 22 s against a 60 s default timeout. The production values give 3× that
headroom, which is an estimate and is labelled as one in the file: the deployment session must
confirm on the real box that the run *completes* (`ordersNotFulfilled: 0`), not merely that it stops
reporting a timeout. If the burst genuinely cannot keep up there, the next lever is lowering
submission concurrency, not widening further.

Verified the profile is actually active in the cluster:

```
The following 1 profile is active: "production"
```

---

## ADR-010

`docs/adr/ADR-010-k3s-on-a-dedicated-hetzner-vps-for-the-public-demo.md` — checked `docs/adr/` first;
ADR-009 was still the highest number. Follows the ADR-007/009 structure (Context, Decision,
Alternatives considered, Consequences and tradeoffs). Records k3s on a dedicated CX23, the separate
per-session CPX32 dev box, always-on, manual `kubectl apply`, and the sizing rationale built on
Phase 10's own 3.825 GiB measurement with CPU — not memory — as the real constraint, with T1–T4 as
the mitigation. The alternatives section is sourced from `deployment-platform-options.md` §5–§7
(managed Kubernetes, EKS specifically, PaaS, Oracle) plus the rejected `/demo` authentication, which
was worth recording because it looks like the obvious answer.

---

## W8 — Docs

- **`README.md`** — new "Live demo" section near the top: states plainly that there is no public URL
  yet because the server is provisioned in a separate session, and that when it exists it is a
  shared public sandbox where anyone can run scenarios, several people may be running them at once,
  and state may reset while you are looking at it.
- **`README.md` `kind` section** — a paragraph inside the apply step stating the production
  overlay's existence and purpose, and that it does not affect the commands above.
- **`infrastructure/kubernetes/production/README.md`** — rewritten to cover the whole overlay:
  layout table, deploy sequence, what each piece changes and why, W7's credential handling (kept,
  and simplified now that the overlay simply omits `01-secrets.yaml` rather than requiring a
  file-by-file apply loop), and a reproducible local verification procedure including the Traefik
  install.
- **`docs/architecture-diagram.md`** — one short paragraph after the "three things the diagram is
  meant to make obvious" list. It earns its place: in production the *only* routed paths are the
  `UI -->` arrows, while the `SCN -->` arrows into Payment/Inventory/Fulfillment stay
  cluster-internal. A reader would otherwise reasonably assume the browser can reach the same
  endpoints Scenario Service can.
- Nothing under `docs/planning/sprint-1/` was touched.

---

## Local flows: verified unchanged, not assumed

- `kubectl apply -f infrastructure/kubernetes/` on a clean `kind` cluster: all 8 pods Ready, all six
  NodePorts serving on their mapped host ports, frontend on 5173 — output above.
- `kubectl apply -f infrastructure/kubernetes/ --dry-run=client` after all changes: every object
  parses. `kubectl apply -f` on a directory is not recursive, so the new `production/common`,
  `production/ghcr` and `production/local-verify` YAML files are correctly ignored by the local
  command.
- `docker-compose.yml` sets no `SPRING_PROFILES_ACTIVE`, so nothing in
  `application-production.yml` (widened timeouts, idle auto-reset) can affect the Compose flow.
- The default `docker build` for the frontend still bakes in `localhost:808X`; only the explicit
  `--build-arg` build produces the `/svc/*` bundle. Both images were built and both were used —
  `frontend:local` for the base run, `frontend:local-prod` for the overlay run.
- The `kind` cluster and the temporary `frontend:local-prod` image were deleted afterwards; the
  six `:local` images that existed before this session remain (rebuilt from current source).

---

## Needs a human decision or follow-up

1. **The repo has no `origin` remote.** `ghcr/kustomization.yaml` therefore carries an `OWNER`
   placeholder that must be replaced once, and the GitHub Actions workflow has never executed. The
   workflow itself is owner-agnostic (`${{ github.repository }}`), so pushing the repo to GitHub is
   the only prerequisite. GHCR packages are created **private** — they must be flipped to public
   once, or k3s cannot pull without an `imagePullSecret`.
2. **The firewall half of the NodePort defense is not in this repo.** The overlay makes the six
   Services `ClusterIP`; the box must also block 30000–32767. Belongs to the demo box's
   provisioning, not the dev VPS.
3. **`host:` and TLS are deliberately absent from the Ingress**, so the rules apply to any hostname
   pointed at the box — which means the allowlist cannot be sidestepped by hitting the node IP
   directly. Adding `host:` and a `tls:` block at deploy time changes no path.
4. **Pre-existing: an unmapped path returns 500, not 404.** `GET /demo-secret` on scenario-service
   produced `{"status":500,"code":"INTERNAL_ERROR","message":"Unexpected server error"}`. That is
   the global exception handler treating Spring's "no handler for this path" as an internal error,
   and it is unrelated to this work (reachable identically via NodePort before any of it). Worth a
   line in the Bug Hunt goal; not fixed here, since it is out of scope and touching the shared error
   handler mid-deployment-work is exactly the kind of "while I'm in here" change the sprint workflow
   says to defer.
5. **Traefik CRD group was assumed to be `traefik.io/v1alpha1`** (Traefik v3), which is what the
   verification ran against. `ingress.yaml`'s header carries the two commands to confirm this on the
   box before applying, since a v2 install would need `traefik.containo.us`.
6. **`create-postgres-secret.sh` needs the namespace to exist first.** Not a defect — the documented
   order applies `00-namespace.yaml` first — but it is now the first step of the deploy sequence in
   the production README rather than an implicit assumption, because getting it wrong fails with a
   confusing `namespaces "orderfulfillment" not found`.
