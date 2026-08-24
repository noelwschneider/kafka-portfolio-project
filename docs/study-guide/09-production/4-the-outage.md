# 9.4 — The outage

[← Tuning for a small box](3-tuning-for-a-small-box.md) · [Chapter 9 ↑](README.md)

ADR-011. One routine command took the public demo down, the fix took it down again a different way,
and the whole thing is the most instructive incident in the project.

---

## The command

```bash
kubectl rollout restart deployment -n orderfulfillment
```

Restart everything in the namespace. Utterly routine. It **took the public demo box fully down**, and
recovery needed two reboots and manual intervention.

---

## Why it did that

> Kubernetes' default rolling-update strategy for a Deployment is `maxSurge: 25%, maxUnavailable: 25%`,
> which **for a single-replica Deployment means: start the new pod, and keep the old one running until
> the new one is Ready.**

That default is excellent — zero-downtime updates. And at one replica it means **two pods**, briefly.

> Restarting several single-replica Deployments in the same command starts all of their new pods while
> all of their old pods are still up, so **for a window the box has to hold double the fleet in
> memory.**

Four services × double = eight JVMs on a 4 GB box with no swap.

And Sprint 2 had just made each one heavier:

> Sprint 2 added a real per-service memory cost on top of that window — an **outbox-dispatcher thread
> and a retention-scheduler thread per service** — and the box had no headroom for it.

[Chapter 6](../06-outbox/README.md)'s rollout and
[Chapter 4](../04-reliability/README.md)'s retention schedulers each added a thread per service. Small
individually. Multiplied by four services and then by two during a rollout, not small.

## Why it did not recover

This is the part that turns a spike into an outage:

> Memory hit 100% with no swap to absorb the spike, the box began thrashing, and **the k3s API server
> itself became unresponsive.** That last part is what turned a resource spike into an outage that did
> not self-resolve: **the API server was too overloaded to observe that the new pods had failed
> readiness, so it never scaled the old ReplicaSets back down either.** Recovery took two box reboots
> and manually scaling the stale ReplicaSets to 0.

**Kubernetes' self-healing runs on the control plane, and on a single-node cluster the control plane
is on the same machine as the workload.** Starve the node and you starve the thing whose job is to
notice.

The system that would have rolled back could not observe that a rollback was needed, so both
ReplicaSets stayed up, which kept memory at 100%, which kept the API server unresponsive. A stable
failure state that required an outside force — a reboot — to break.

**Self-healing is not self-healing when the healer shares the resource.** That is the transferable
insight, and it applies to any single-node cluster, control-plane-on-workload-node setup, or monitoring
agent running inside the thing it monitors.

---

## The fix, and the second outage

`maxSurge: 0`, verified live, held the fleet to its steady-state footprint through a full five-service
redeploy.

> But **the very next redeploy against the fixed process took the box down again**, by a different
> route, needing one more reboot to clear: the inventory-service HPA reacted to the CPU spike that five
> JVMs cold-starting at once naturally produce — class loading and Spring context init, not real
> request load — and **added two more inventory-service replicas during the exact window the box had
> the least spare memory.**

[Chapter 8](../08-observability-and-scaling/4-the-autoscaler.md) tells this from the autoscaler's side.
Here is the sentence that matters:

> `maxSurge: 0` prevents a service from ever running two versions of itself; **it does nothing to stop
> the HPA from independently deciding the moment right after a deploy is when inventory-service needs
> *more* replicas.**

Two independent controllers, each correct, with no shared understanding of what was happening. The
Deployment controller carefully avoided doubling memory. The HPA then added replicas anyway — because
CPU was genuinely high, and it had no way to know why.

**Fixing one contributor to a multi-factor failure leaves you exposed to the others**, and the fix can
even *expose* them: a working deploy is exactly what let the HPA see the cold-start spike it would
previously have been drowned out by.

---

## Three changes, at three layers

> Two changes, both scoped to the production overlay only — local `kind` development has no memory
> constraint remotely like this and should not pay for it with slower or interrupted restarts.

**1. `maxSurge: 0, maxUnavailable: 1` on all five backend Deployments.**

> This tears the old pod down before the new one starts, so a single service's rolling update **never
> needs more than that service's own steady-state memory** — it cannot recreate the double-fleet
> condition on its own.

Scope decisions worth noting. **Scenario Service is included** though it was not in the incident:
*"it has the same per-service memory profile and the same risk on any future deploy."* Fix the class,
not the instance.

**Frontend, Kafka and Postgres are excluded**, each for a stated reason: the frontend is lightweight
stateless nginx, and the two stateful services are single-replica with `ReadWriteOnce` volumes where
*"a surge pod likely could not even schedule (only one pod can hold an RWO volume at a time); changing
a stateful service's rollout strategy is out of scope here."*

The cost, stated: *"a few seconds of per-service unavailability while the new pod starts, which is
acceptable for this demo."* Zero-downtime deploys traded away deliberately, because on this box the
surge is what causes downtime.

**2. `redeploy.sh` — one service at a time.**

> `maxSurge: 0` alone stops any *one* Deployment from surging, but does nothing to stop five separate
> `rollout restart` commands, issued together, from tearing down and starting up five services at once
> — still a real memory and CPU spike, just a smaller one than before. Doing it one Deployment at a
> time, and confirming health before continuing, keeps the peak footprint to **"steady state plus one
> service restarting"** and turns a stuck rollout into an **immediate, loud failure** instead of one
> that compounds by starting the next restart on top of it.

Two properties, and the second is the more valuable.

**Bounded peak** — steady state plus one service.

**Failing loudly and early.** The script waits for `kubectl rollout status` before continuing, so a
service that does not come up **stops the deploy**. In the incident, each failure made the next one
more likely; sequencing turns a compounding cascade into a single clean stop.

`set -euo pipefail` at the top of the script is the same instinct in bash: fail on error, fail on
undefined variable, fail on a broken pipe.

**3. A 60s scale-up stabilization window on the HPA.**

Covered in [Chapter 8](../08-observability-and-scaling/4-the-autoscaler.md). Note the restraint:

> The HPA's CPU threshold (65% of the 150m request) is unchanged — **it's already validated against
> real Scenario 8 load and wasn't the problem.** The problem was reacting to a CPU reading *instantly*.

---

## What this incident teaches

**Defaults are tuned for the common case, and a single-replica Deployment is not it.**
`maxSurge: 25%` assumes replicas to spare. At one replica it means 100% surge, which is the opposite of
what the percentage suggests.

**Memory spikes on a swapless box are cliffs, not slopes.** No swap means no degraded mode: fine, fine,
fine, thrashing.

**On a single-node cluster, the control plane is a workload.** Starve the node and you lose the
mechanism that would have recovered it.

**Independent controllers do not coordinate.** The Deployment controller and the HPA both did their
jobs. Nothing was looking at the whole picture — and nothing will, unless you are.

**Fix the class, not the instance.** Scenario Service was not involved and was included anyway.

**Don't change what was already validated.** The 65% threshold survived because it was not the problem,
and changing it would have invalidated the one number known to be right.

---

## What the demo box actually is now

- **One Hetzner CX23**, 2 vCPU / 4 GB, always on, ~€6/month.
- **k3s**, with the base manifests plus a production overlay.
- **One hostname**, TLS at Cloudflare, everything behind a Traefik Ingress **allowlist**.
- **No NodePorts.** The consumer-pause and payment-override endpoints are unroutable from outside.
- **Heap caps, a startup probe, a TCP broker probe**, and widened scenario timeouts.
- **Sequential deploys** via `redeploy.sh`, `maxSurge: 0`, and an HPA that waits 60 seconds before
  believing a CPU spike.
- **Idle auto-reset** after 15 minutes, so an abandoned session cleans itself up.

A recruiter can open a link, run all eight scenarios, watch real events cross five services, and cannot
wedge it — and if they somehow do, it fixes itself in a quarter of an hour.

---

[← Tuning for a small box](3-tuning-for-a-small-box.md) · [Chapter 9 ↑](README.md) · [Chapter 10 — Retrospective →](../10-retrospective/README.md)
