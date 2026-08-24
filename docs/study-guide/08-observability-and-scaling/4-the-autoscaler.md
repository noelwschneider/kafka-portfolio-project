# 8.4 — The autoscaler

[← Scaling](3-scaling.md) · [Chapter 8 ↑](README.md)

Sprint 2 goal 6. Turning Phase 10's manual `kubectl scale` into a `HorizontalPodAutoscaler` — and then
learning something from it the hard way.

---

## Why it waited

ADR-007 deferred the HPA past Phase 8, and the sequencing is the point:

1. **Phase 8** — Deployments and Services. No autoscaling.
2. **Phase 10** — scale by hand, measure what happens, find the ceiling.
3. **Sprint 2** — encode the measured behavior as an autoscaler.

An HPA written in Phase 8 would have needed a target CPU percentage, a replica ceiling, and
stabilization windows — every one of them guessed. By Sprint 2, each was **derived from something
observed**.

---

## The manifest

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef:
    kind: Deployment
    name: inventory-service
  minReplicas: 1
  maxReplicas: 3
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 65
```

An HPA is a controller: read a metric, compare against a target, adjust the replica count, repeat.

### `maxReplicas: 3` is not a budget

> `orders.events` has a fixed 3-partition count, and a Kafka consumer group can never usefully have
> more running consumers than partitions — a 4th replica would sit idle with no partition assigned.
> **3 is the actual ceiling of "more replicas helps," not an arbitrary cap.**

This is the single best detail in the manifest. Most `maxReplicas` values are cost limits or guesses.
This one is a **property of the system** — beyond 3, the autoscaler would add pods that consume
memory, take a scheduling slot, and process nothing.

An HPA that could scale past the partition count would actively make things worse under load, which is
the opposite of what an autoscaler is for.

### `averageUtilization: 65` is relative to the request

```yaml
# Against the 150m CPU request in 05-inventory-service.yaml: scale up once average usage across
# replicas passes ~97m. Deliberately below 100% so the HPA reacts before the container is fully
# CPU-starved, not after.
```

**CPU utilization in an HPA is a percentage of the *request*, not of the node or the limit.** 65% of a
150m request is ~97m — and since the *limit* is 500m, a pod at "65% utilization" is using about a fifth
of what it is allowed. Reading this as "65% of capacity" is a common and consequential misreading.

Targeting below 100% is deliberate: an autoscaler that waits for saturation is scaling up *after* the
damage. Pods take time to schedule, pull, and start — a JVM especially — so the trigger has to lead the
problem.

### metrics-server

> Requires metrics-server or an equivalent `metrics.k8s.io` provider — **the HPA controller has nothing
> to read CPU from otherwise.**

Not installed by default in kind (hence `11-metrics-server.yaml`), and bundled with k3s — so the
production overlay omits it. An HPA with no metrics source reports `<unknown>/65%` and does nothing,
silently.

---

## The scale-down window

```yaml
scaleDown:
  # Default HPA behavior scales down almost immediately once utilization drops, which reads as
  # flappy in a demo (add a pod, drop it 30s later, add it back). A 2-minute stabilization window
  # means the scale-down decision looks at the max recommendation over the last 2 minutes, so a
  # brief dip mid-burst doesn't undo a scale-up that's still needed.
  stabilizationWindowSeconds: 120
```

A **stabilization window** makes the controller consider the *maximum* recommendation over the window
rather than the instantaneous one. A momentary dip cannot undo a scale-up that is still needed.

Asymmetric on purpose: scaling down too eagerly costs you the pod you are about to need again, plus
another cold start.

---

## The scale-up window, and the incident behind it

This is the best operational story in the project.

```yaml
scaleUp:
  # Originally 0s (react instantly) so a burst's scale-up is visible while it's still draining. A
  # same-night incident showed the cost of that: right after a deploy restarts all five backend
  # services (see ADR-011), five JVMs cold-starting at once produce a CPU spike from class loading
  # and Spring context init, not real request load — and at 0s stabilization the HPA read that
  # spike as sustained demand and added two more inventory-service replicas during the exact window
  # the box had the least spare memory, causing a second outage on top of the first.
  stabilizationWindowSeconds: 60
```

Follow the chain:

1. A deploy restarts all five backend services at once.
2. Five JVMs cold-start simultaneously. Class loading and Spring context initialization are
   **CPU-intensive** — for tens of seconds, and for reasons having nothing to do with load.
3. The HPA, with **zero** scale-up stabilization, reads that spike as sustained demand.
4. It adds two more Inventory Service replicas — **during the exact window the box had least spare
   memory**, because five JVMs were already starting.
5. Two more JVMs start. The node runs out. **A second outage, on top of the first.**

The autoscaler, working exactly as configured, converted a rough deploy into an outage. Every
individual decision was correct given its inputs; the inputs were misleading.

**Startup CPU is not load.** An autoscaler that cannot tell them apart will amplify every restart into
a scale-up, at precisely the moment the system has least headroom.

The fix, and why 60 seconds specifically:

> 60s filters that out: cold-start CPU settles within tens of seconds once the JVM finishes
> initializing, while a genuine Scenario 8 burst (which drains over 12–22s but keeps inventory-service
> busy for longer than that as orders queue) stays elevated well past 60s and still triggers a real
> scale-up — just not an instantaneous one.

**A threshold chosen to sit between two measured durations.** Cold-start CPU settles in tens of
seconds; genuine burst load stays elevated past 60. The window separates them. Both numbers came from
observation.

And the discipline in what was *not* changed:

> The CPU threshold itself (65%) is unchanged: it's already validated against real load and isn't what
> caused this.

**Change the thing that caused the problem, not everything nearby.** 65% was validated; the incident
was about *duration*, not *level*. Adjusting both would have invalidated the one number that was known
good.

This also connects to [Chapter 9](../09-production/README.md): the deploy that restarted all five
services at once is ADR-011, and the fix there — sequential, wait-for-health rollouts — addresses the
same incident from the other end. **Two fixes to one incident, at two layers**, neither sufficient
alone.

---

## Verified, not asserted

> Verified for real on the Hetzner dev box running Scenario 8 against a live `kind` cluster — real
> `kubectl get hpa` / `kubectl describe hpa` output, not a hypothetical: CPU utilization crossed the
> 65% target after the burst's submitted orders started draining, the HPA rescaled Inventory Service
> from 1 to 2 replicas (`SuccessfulRescale ... New size: 2; reason: cpu resource utilization
> (percentage of request) above target`), and once the backlog drained and utilization stayed low past
> the stabilization window it scaled back down to 1 (`SuccessfulRescale ... New size: 1; reason: All
> metrics below target`).

Both directions, with the controller's own event messages quoted as evidence.

Note *where*: the Hetzner dev box, because the laptop could not do it. Phase 10 hit the ~3.8GB Docker
Desktop ceiling; Sprint 2 provisioned a machine with headroom specifically so the demonstration could
be completed. **The infrastructure workstream existed because a measurement was blocked** — which is a
better reason to provision a box than "it would be convenient."

---

## What an HPA is and is not

**Is:** a controller that keeps a metric near a target by adjusting replica count, within bounds you
set.

**Is not:**

- **Instant.** Scheduling, image pull, JVM start, readiness — tens of seconds before a new pod helps.
  It cannot absorb a spike shorter than that.
- **Aware of your architecture.** It does not know about partition counts. `maxReplicas: 3` is
  knowledge *you* supplied.
- **A fix for a saturated dependency.** More consumers against one PostgreSQL instance move the
  bottleneck rather than removing it.
- **Free of feedback loops.** The incident above is exactly one: the autoscaler's action made the
  condition it was reacting to worse.

The right framing: an HPA handles **sustained, gradual** changes in demand. It is the wrong tool for
spikes shorter than a pod start, and a dangerous one if it can misread a non-load signal as load.

---

## Chapter 8 in one paragraph

Correlation IDs that had been propagating correctly since Chapter 3 finally became visible, once
structured logging rendered them *and* an audit found that four of five services logged nothing at all
on a successful run. Metrics arrived through Actuator and Micrometer with request latency, JVM state,
connection-pool pressure and consumer lag for free, and a CORS trap that made health checks work under
`curl` and fail in a browser. Scaling demonstrated the property that actually matters — a Kafka
consumer group cannot usefully exceed its partition count — and found the laptop's ceiling before the
architecture's. And the autoscaler that encoded all of it turned a rough deploy into an outage by
mistaking JVM cold-start CPU for demand, which is now sixty seconds of stabilization and a very good
story.

---

[← Scaling](3-scaling.md) · [Chapter 8 ↑](README.md) · [Chapter 9 — Production →](../09-production/README.md)
