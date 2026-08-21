# ADR-011: Sequential, wait-for-health rollouts on the production overlay

- **Status:** Accepted. Implemented: `maxSurge: 0` on the five backend Deployments in
  `infrastructure/kubernetes/production/common/patch-tuning.yaml`, a new
  `infrastructure/kubernetes/production/redeploy.sh` script, and a 60s scale-up stabilization
  window on the inventory-service HPA (`infrastructure/kubernetes/10-inventory-service-hpa.yaml`).
- **Date:** 2026-08-21

## Context

`kubectl rollout restart deployment -n orderfulfillment`, run against all four backend Deployments
that existed at the time (order/inventory/payment/fulfillment-service), took the public demo box
(Hetzner CX23, 2 vCPU / 4 GB, no swap — ADR-010) fully down.

Kubernetes' default rolling-update strategy for a Deployment is `maxSurge: 25%, maxUnavailable: 25%`,
which for a single-replica Deployment means: start the new pod, and keep the old one running until
the new one is Ready. Restarting several single-replica Deployments in the same command starts all
of their new pods while all of their old pods are still up, so for a window the box has to hold
double the fleet in memory. Sprint 2 added a real per-service memory cost on top of that window — an
outbox-dispatcher thread and a retention-scheduler thread per service
(`docs/adr/ADR-006-transactional-outbox-for-db-kafka-consistency.md`) — and the box had no headroom
for it. Memory hit 100% with no swap to absorb the spike, the box began thrashing, and the k3s API
server itself became unresponsive. That last part is what turned a resource spike into an outage
that did not self-resolve: the API server was too overloaded to observe that the new pods had failed
readiness, so it never scaled the old ReplicaSets back down either. Recovery took two box reboots
(`hcloud server reboot`) and manually scaling the stale ReplicaSets to 0.

`maxSurge: 0` and `redeploy.sh` (below) fixed that; verified live, they held the fleet to its
steady-state footprint through a full five-service redeploy. But the very next redeploy against the
fixed process took the box down again, by a different route, needing one more reboot to clear: the
inventory-service HPA (`maxReplicas: 3`) reacted to the CPU spike that five JVMs cold-starting at
once naturally produce — class loading and Spring context init, not real request load — and added
two more inventory-service replicas during the exact window the box had the least spare memory.
`maxSurge: 0` prevents a service from ever running two versions of itself; it does nothing to stop
the HPA from independently deciding the moment right after a deploy is when inventory-service needs
*more* replicas.

## Decision

Two changes, both scoped to the production overlay only — local `kind` development has no memory
constraint remotely like this and should not pay for it with slower or interrupted restarts:

1. **`maxSurge: 0, maxUnavailable: 1` on all five backend Deployments**
   (order/inventory/payment/fulfillment/scenario-service), added to the existing per-Deployment
   patches in `infrastructure/kubernetes/production/common/patch-tuning.yaml` rather than a new
   file, consistent with that file already holding other production-only Deployment patches (heap
   caps, probe timing). This tears the old pod down before the new one starts, so a single service's
   rolling update never needs more than that service's own steady-state memory — it cannot recreate
   the double-fleet condition on its own. scenario-service is included even though it was not part
   of this incident: it has the same per-service memory profile and the same risk on any future
   deploy. Frontend, Kafka and Postgres are unchanged — frontend is lightweight, stateless nginx, and
   Kafka/Postgres are single-replica with `ReadWriteOnce` local-path volumes, where a surge pod
   likely could not even schedule (only one pod can hold an RWO volume at a time); changing a
   stateful service's rollout strategy is out of scope here.

2. **`infrastructure/kubernetes/production/redeploy.sh`**, which restarts the five backend
   Deployments one at a time, waiting for `kubectl rollout status` to report success before moving
   to the next. `maxSurge: 0` alone stops any *one* Deployment from surging, but does nothing to
   stop five separate `rollout restart` commands, issued together, from tearing down and starting up
   five services at once — still a real memory and CPU spike, just a smaller one than before. Doing
   it one Deployment at a time, and confirming health before continuing, keeps the peak footprint to
   "steady state plus one service restarting" and turns a stuck rollout into an immediate, loud
   failure instead of one that compounds by starting the next restart on top of it.
   `infrastructure/kubernetes/production/README.md`'s redeploy instructions now point at this script
   instead of the raw multi-deployment restart command.

3. **60s scale-up stabilization window on the inventory-service HPA**
   (`infrastructure/kubernetes/10-inventory-service-hpa.yaml`), up from the original 0s. The HPA's
   CPU threshold (65% of the 150m request) is unchanged — it's already validated against real
   Scenario 8 load and wasn't the problem. The problem was reacting to a CPU reading *instantly*: a
   cold JVM's startup cost looks identical to real load for the first several seconds, and only a
   stabilization window can tell the two apart. 60s is long enough that a deploy's cold-start spike
   settles before the HPA acts on it, while a genuine burst — which keeps inventory-service busy well
   past 60s as orders queue, not just for the few seconds the burst itself takes to drain — still
   triggers a real scale-up, just not an instantaneous one. Applies to `kind` too, since the HPA
   manifest isn't overlay-specific (unlike items 1 and 2 above) — this is a real property of
   `HorizontalPodAutoscaler` behavior, not a CX23-only workaround.

## Alternatives considered

**A second Hetzner CX23 as a k3s agent node (~€5.99/month more).** Would add real headroom and turn
the box into a genuine multi-node cluster, which ADR-010 already flags as a better story than a
single node. Deferred, not rejected: this process fix is free and directly addresses the root cause
(simultaneous multi-service rollouts, not insufficient capacity in the steady state), so it is worth
trying first. A second node stays available if sequential rollouts alone prove insufficient — for
example, if a single service's own restart footprint turns out to be tight even in isolation.

**Waiting for Hetzner to offer a better-suited plan.** Not actionable on any useful timeline, and it
does not address the root cause even if it eventually happens — a bigger box does not stop `kubectl
rollout restart deployment` from surging every Deployment in the namespace at once. Rejected.

## Consequences and tradeoffs

- A production redeploy now takes longer wall-clock time than the old one-shot command: five
  sequential rollouts instead of one parallel one, each waiting out its own `rollout status`.
  Acceptable for a manual-ops demo box with no deployment pipeline (ADR-010) redeployed
  infrequently.
- Each service is briefly unavailable (no pod handling its traffic) during its own restart, rather
  than briefly running two versions. For a demo box this is the right trade — a few seconds of one
  service being down is far cheaper than the failure mode this ADR closes.
- `maxSurge: 0` and `redeploy.sh` are two independent defenses of the same property (never running
  double the fleet in memory) at two different scopes — one Deployment's own rollout, and the set of
  Deployments restarted together — the same two-independent-layers pattern ADR-010 already uses for
  the ingress allowlist and the `ClusterIP` patch.
- Anyone who reaches for the raw `kubectl rollout restart deployment -n orderfulfillment` instead of
  `redeploy.sh` — out of habit, or because they did not read the README — reopens the multi-service
  half of this problem, even with `maxSurge: 0` in place. The README now says not to use it and why.
- The HPA now takes up to 60s longer to visibly add a pod once a real burst starts, versus reacting
  the instant utilization crosses the threshold. For a live demo where the point is watching a pod
  get added, this is a real cost — but the original 0s setting is exactly what caused the second
  outage, and a threshold that reacts to noise isn't a demo worth watching either.
