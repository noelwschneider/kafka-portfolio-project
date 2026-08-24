# HPA Scaling Demo

**Scope:** Sprint 2 goal 6 ("Autoscaler (HorizontalPodAutoscaler)",
`docs/planning/sprint-2/pre-sprint-planning.md` item 8) — formalize the manual-scaling story
`docs/agent-reports/sprint-1/phase-10-scaling-demo.md` measured by hand into an actual
`HorizontalPodAutoscaler`, wire real metrics to feed it, and capture a genuine scale-up and
scale-down against real load. Run on the Hetzner dev box
(`docs/agent-reports/sprint-2/hetzner-dev-box-setup.md`), not the laptop, for the same reason Phase
10 needed it: the laptop's Docker Desktop VM has a proven ~3.8GB ceiling that a meaningful scaling
demo needs headroom past.

---

## 1. What was built

- `infrastructure/kubernetes/10-inventory-service-hpa.yaml` — `autoscaling/v2` HPA targeting
  `Deployment/inventory-service`. `minReplicas: 1`, `maxReplicas: 3` (the Kafka partition ceiling on
  `orders.events` — a 4th replica would get no partition), CPU target 65% of the existing 150m
  request. Custom `behavior`: scale-up has no stabilization delay (react fast to a burst),
  scale-down uses a 2-minute stabilization window (don't flap a pod in and out right after a burst
  clears).
- `infrastructure/kubernetes/11-metrics-server.yaml` — upstream metrics-server v0.9.0
  (`kubernetes-sigs/metrics-server`'s released `components.yaml`), unmodified except for one added
  arg, `--kubelet-insecure-tls`, needed because `kind` kubelets don't have certs a real CA signed.
  Applied automatically wherever `kubectl apply -f infrastructure/kubernetes/` already runs (local
  `kind` and the dev-box `kind` cluster) — nothing else needed to run the file.
- `infrastructure/kubernetes/production/common/kustomization.yaml` — added the HPA to the production
  overlay's resource list. Deliberately did **not** add the metrics-server file there: k3s (what the
  public demo box runs, ADR-010) bundles its own metrics-server by default, and layering this
  project's kind-tuned copy on top would install a second, conflicting one.
- `docs/architecture-diagram.md` — new "6. Scaling" section tying Phase 10's manual story to this
  HPA and this report. No new ADR: per `.claude/CLAUDE.md`'s guidance, this formalizes existing
  scaling behavior rather than introducing a new architectural pattern.

**Reused vs. new infrastructure (Agent Rule 11):** the project already runs Prometheus, but only
under Docker Compose scraping each service's `/actuator/prometheus`
(`infrastructure/observability/prometheus.yml`) — that stack isn't deployed into any Kubernetes
cluster, and the HPA v2 API reads CPU/memory from the separate `metrics.k8s.io` aggregated API,
which only metrics-server (or a meaningfully heavier Prometheus Adapter shim for the same result)
serves. metrics-server is additive cluster infrastructure, not a duplicate of the existing
Prometheus setup, and doesn't replace it.

## 2. Why Inventory Service

Scenario 8 ("High-Volume Batch", `docs/scenarios.md`) is written to stress exactly this service: it
consumes every `OrderCreated` off `orders.events` and does a reservation write per order. Phase 10
already scaled this exact service by hand under this exact scenario and measured throughput/lag
moving with replica count. `orders.events`' fixed 3-partition count sets a real ceiling on how many
replicas can help at all, which is why `maxReplicas: 3` isn't an arbitrary number.

## 3. Environment

Hetzner dev box (`kafka-dev-box` SSH alias), cpx32 (4 vCPU / 8GB), restored from the existing
snapshot via `./dev-up.sh`. `kind` cluster `orderfulfillment`, same `infrastructure/kind-config.yaml`
Phase 8/10 used.

**Build note — a concurrent working-tree conflict, worked around, not fixed:** at the time this
session ran, the shared working tree had uncommitted WIP from a different in-flight goal (the
transactional-outbox reliability work) that left `InventoryReservationExecutorTest.java` out of sync
with `InventoryReservationExecutor`'s constructor — a genuine compile break, not something this
session introduced or should fix (out of scope, and not this session's code to touch). Rather than
edit someone else's in-progress change, this session built and deployed from a throwaway `git
worktree` checked out at `HEAD` (last commit, `1a81745`), with this session's own two new manifest
files and the one `kustomization.yaml` edit copied in on top. That worktree compiled clean
(`mvn -pl services/common,services/inventory-service -am test-compile` — BUILD SUCCESS) and is what
was actually pushed to and run on the dev box. The main working tree itself was left untouched by
this session apart from the files listed in §1 — the outbox WIP is exactly as this session found it.

Images built via `docker compose build` (6 services), tagged `<service>:local`, loaded with
`kind load docker-image`. Base manifests applied with
`kubectl apply -f infrastructure/kubernetes/` — this also applied the new HPA and metrics-server
files, since they're just more files in that directory. All 8 app pods plus `metrics-server` reached
Ready on the first attempt (`kubectl wait --for=condition=ready pod --all --timeout=300s`, then the
same for `metrics-server` in `kube-system`).

Confirmed `metrics-server` was actually serving before generating load:

```
$ kubectl top pods -n orderfulfillment
NAME                                   CPU(cores)   MEMORY(bytes)
frontend-7548b54496-27psr              1m           4Mi
fulfillment-service-6fbc6594f7-pqxtz   274m         251Mi
inventory-service-7c6747cb47-bjk7w     217m         256Mi
kafka-96c548bcd-l6q5l                  374m         284Mi
order-service-66fdd9d946-wfs9w         469m         249Mi
payment-service-b47696cf6-g6fwk        272m         252Mi
postgres-57cf75bddf-zm5tb              12m          108Mi
scenario-service-797599d8c4-kr6fr      501m         280Mi
```

`kubectl get hpa` initially showed `cpu: <unknown>/65%` for a few seconds while metrics-server's
first 15s scrape window populated (`FailedGetResourceMetric` events during that window, expected and
transient), then settled to reading real numbers, e.g. `cpu: 26%/65%` at rest before any scenario
load.

## 4. Real load, real scale-up, real scale-down

Reset the demo environment and ran Scenario 8 for real (`POST /demo/reset`, then
`POST /demo/scenarios/high-volume`) against the live cluster — real HTTP requests, real Kafka
records, real Postgres writes, nothing simulated (Agent Rule 10). Polled `kubectl get hpa`,
`kubectl get pods -l app=inventory-service`, and the scenario run's own status every 5 seconds
throughout.

Full 5-second-interval transcript (`pods=` is the live Inventory Service pod count,
`REPLICAS` is the HPA's `.status.currentReplicas`):

```
14:26:37 | pods=1 | cpu: 18%/65% | REPLICAS=1 | scenario: RUNNING
14:26:43 | pods=1 | cpu: 18%/65% | REPLICAS=1 | scenario: RUNNING
14:26:48 | pods=1 | cpu: 17%/65% | REPLICAS=1 | scenario: COMPLETED
14:26:53 | pods=1 | cpu: 17%/65% | REPLICAS=1 | scenario: COMPLETED
14:26:59 | pods=2 | cpu: 86%/65% | REPLICAS=1 | scenario: COMPLETED   <- breach; new pod already scheduled
14:27:04 | pods=2 | cpu: 86%/65% | REPLICAS=1 | scenario: COMPLETED
14:27:09 | pods=2 | cpu: 86%/65% | REPLICAS=1 | scenario: COMPLETED
14:27:14 | pods=2 | cpu: 74%/65% | REPLICAS=2 | scenario: COMPLETED   <- HPA status caught up to 2
14:27:20 | pods=2 | cpu: 74%/65% | REPLICAS=2 | scenario: COMPLETED
14:27:25 | pods=2 | cpu: 74%/65% | REPLICAS=2 | scenario: COMPLETED
14:27:30 | pods=2 | cpu: 28%/65% | REPLICAS=2 | scenario: COMPLETED
   ... (utilization stays under target, 13-32%, for the 2-minute scale-down stabilization window) ...
14:29:14 | pods=2 | cpu: 24%/65% | REPLICAS=2 | scenario: COMPLETED
14:29:19 | pods=1 | cpu: 24%/65% | REPLICAS=2 | scenario: COMPLETED   <- scale-down begins
14:29:24 | pods=1 | cpu: 24%/65% | REPLICAS=2 | scenario: COMPLETED
14:29:30 | pods=1 | cpu: 14%/65% | REPLICAS=1 | scenario: COMPLETED   <- HPA status caught up to 1
14:29:35 | pods=1 | cpu: 14%/65% | REPLICAS=1 | scenario: COMPLETED
```

The CPU spike (18% -> 86%) landed a few seconds *after* the scenario itself reported `COMPLETED` —
consistent with Phase 10's finding that Inventory Service's real work is consuming and processing the
backlog off `orders.events` after the submission burst, not the submission calls themselves.

Official controller-side confirmation, `kubectl describe hpa` and `kubectl get events`:

```
$ kubectl describe hpa inventory-service -n orderfulfillment
...
Events:
  Type     Reason                        Age    From                       Message
  ----     ------                        ----   ----                       -------
  Warning  FailedGetResourceMetric       5m4s   horizontal-pod-autoscaler  failed to get cpu utilization: unable to get metrics for resource cpu: unable to fetch metrics from resource metrics API: the server is currently unable to handle the request (get pods.metrics.k8s.io)
  Warning  FailedGetResourceMetric       4m34s  horizontal-pod-autoscaler  failed to get cpu utilization: did not receive metrics for targeted pods (pods might be unready)
  Normal   SuccessfulRescale             3m34s  horizontal-pod-autoscaler  New size: 2; reason: cpu resource utilization (percentage of request) above target
  Normal   SuccessfulRescale             79s    horizontal-pod-autoscaler  New size: 1; reason: All metrics below target

$ kubectl get events -n orderfulfillment --field-selector involvedObject.name=inventory-service --sort-by='.lastTimestamp'
LAST SEEN   TYPE      REASON               OBJECT                          MESSAGE
5m34s       Normal    ScalingReplicaSet    deployment/inventory-service    Scaled up replica set inventory-service-7c6747cb47 to 1
3m34s       Normal    SuccessfulRescale    horizontalpodautoscaler/inventory-service   New size: 2; reason: cpu resource utilization (percentage of request) above target
3m34s       Normal    ScalingReplicaSet    deployment/inventory-service    Scaled up replica set inventory-service-7c6747cb47 to 2 from 1
79s         Normal    SuccessfulRescale    horizontalpodautoscaler/inventory-service   New size: 1; reason: All metrics below target
79s         Normal    ScalingReplicaSet    deployment/inventory-service    Scaled down replica set inventory-service-7c6747cb47 to 1 from 2
```

The two `FailedGetResourceMetric` warnings above are the transient window before metrics-server's
first scrape populated (§3), not a fault during the actual scaling event — both are timestamped
5m+/4m+ before the `SuccessfulRescale` events and never recur afterward.

**Reading the numbers:** the scale-up trigger (86% against a 65% target) and the scale-down trigger
(utilization at or under target for the full 2-minute stabilization window, then rescale) both
matured exactly the way the HPA's own configured behavior says they should — this is the
HPA's real control loop reacting to real container CPU usage recorded by metrics-server, not a
canned demonstration.

## 5. Cleanup

`kind delete cluster --name orderfulfillment` was not separately necessary — the whole box was torn
down. `./dev-down.sh` run at the end of this session: snapshotted the disk, deleted the server,
pruned old snapshots to the one most recent. Confirmed via `hcloud server list` (empty) after
teardown.

## 6. Not committed

Per this task's instructions, no `git commit` was run. The working tree carries this session's
changes (the two new manifest files, the `kustomization.yaml` edit, the `architecture-diagram.md`
edit, and this report) on top of whatever it already had uncommitted from other in-flight work,
untouched.
