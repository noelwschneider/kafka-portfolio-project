# 10.3 — Found in production

[← Found by looking](2-found-by-looking.md) · [Next: What this adds up to →](4-what-this-adds-up-to.md)

Three failures that only existed on a 2-vCPU box with no swap, and one that only existed because the
demo ran unattended. All of them are about **resource constraints being a different category of
problem** — the code was correct.

---

## The rollout that took the box down

**Where:** the demo box. **Found:** by doing it. **Severity:** total outage requiring two reboots.

```bash
kubectl rollout restart deployment -n orderfulfillment
```

The chain, from [Chapter 9](../09-production/4-the-outage.md):

1. Kubernetes' default `maxSurge: 25%` — which for a **single-replica** Deployment means start the new
   pod and keep the old one until it is Ready.
2. Four services restarted at once = **eight JVMs** on a 4 GB box with no swap.
3. Sprint 2 had just added an outbox-dispatcher thread and a retention-scheduler thread per service, so
   each was heavier than when the box was sized.
4. Memory hit 100%. No swap. The box thrashed.
5. **The k3s API server became unresponsive** — and on a single-node cluster the control plane is on
   the same machine as the workload.
6. So Kubernetes could not observe that the new pods had failed readiness, and never scaled the old
   ReplicaSets back down. A stable failure state.

Recovery: two `hcloud server reboot`s and manually scaling stale ReplicaSets to zero.

**The lesson, and it is the biggest one in this chapter:** *self-healing is not self-healing when the
healer shares the resource.* Every recovery mechanism Kubernetes has runs on the control plane. Starve
the node and you lose the thing whose job is to notice.

Also: **defaults are tuned for the common case, and a single-replica Deployment is not it.**
`maxSurge: 25%` sounds conservative and means 100% surge at one replica.

## The fix that caused a second outage

`maxSurge: 0` worked — verified live through a full five-service redeploy.

> But **the very next redeploy against the fixed process took the box down again**, by a different
> route [...] the inventory-service HPA reacted to the CPU spike that five JVMs cold-starting at once
> naturally produce — class loading and Spring context init, not real request load — and added two more
> inventory-service replicas **during the exact window the box had the least spare memory.**

The autoscaler had a **zero-second** scale-up stabilization window, chosen so a burst's scale-up would
be visible while it was still draining. Perfectly sensible for demonstrating Scenario 8. Catastrophic
during a deploy.

> `maxSurge: 0` prevents a service from ever running two versions of itself; **it does nothing to stop
> the HPA from independently deciding the moment right after a deploy is when inventory-service needs
> *more* replicas.**

**Two lessons.** *Startup CPU is not load* — a cold JVM looks identical to a busy one for the first
several seconds, and only a stabilization window separates them. And *fixing one contributor to a
multi-factor failure leaves the others* — worse, a working deploy is exactly what let the HPA see a
spike it would previously have been drowned out by.

Fixed with a 60-second window, chosen to sit **between two measured durations**: cold-start CPU settles
in tens of seconds, genuine burst load stays elevated well past 60.
[Chapter 8](../08-observability-and-scaling/4-the-autoscaler.md).

And the restraint worth copying: the 65% CPU threshold was **not** changed, because *"it's already
validated against real Scenario 8 load and wasn't the problem."*

---

## The probe that caused what it detected

**Where:** Kafka, everywhere. **Found:** Phase 10, on a laptop. **Severity:** total loss of the broker,
under load.

Compose and the base manifests used `kafka-broker-api-versions.sh` as Kafka's readiness check —
deliberately chosen because it proves the broker actually *answers*, not merely that the process is up.

It **starts a JVM inside the broker container on every check.**

> began timing out under CPU contention and flapped the broker Ready/NotReady, **taking the Kafka
> Service's endpoints to zero.** [...] A probe whose own cost causes the failure it is meant to detect
> is not a probe.

Under contention, the probe cannot get a CPU slice, times out, marks the broker not-ready, and every
client loses the broker — because the health check was too expensive to run.

Replaced with a `tcpSocket` check, with the cost stated: *"a TCP accept proves the listener is bound,
not that the broker will answer a metadata request — a weaker signal, bought by removing the probe's
own CPU cost."* [Chapter 9](../09-production/3-tuning-for-a-small-box.md).

**The lesson:** **a health check is a load.** On a machine with headroom that is invisible. On a
constrained one the observer changes the outcome — and health checks run forever, on every pod, at a
fixed interval, whether or not anything is wrong.

This one is worth flagging as a near-miss rather than an incident: Phase 10 found it on a laptop, and
Sprint 2 fixed it as **blocking work before the deploy**, so it never took down the public demo. That
is the system working.

---

## The reset that silently failed

**Where:** Inventory Service. **Found:** on the live demo. **Severity:** the demo wedged, permanently.

`POST /demo/reset` used the business `PUT /api/inventory/{sku}` to restore seed quantities. That
endpoint correctly rejects an update where `availableQuantity < reservedQuantity`, as an oversold
state.

And:

> reservations are only released on the payment-failure compensation path (**never on successful
> fulfillment**), so `reservedQuantity` accumulates without bound over a long-running demo and will
> routinely exceed any seed value.

So after enough successful orders, reset returned **409** and did nothing. The demo could not be reset,
and `freeQuantity()` was permanently below the seed. Commit `1a81745` — *"fix inventory reset not
clearing reservations, wedging the live demo."*

Two correct behaviors composing into a broken one. The business rule is right. Reservations
accumulating on the success path is a modelling decision, also defensible — a fulfilled reservation is
history, not free stock.

Fixed with `restoreForDemo`, a demo-only operation that zeroes both fields together, deliberately
bypassing the business guard. [Chapter 5](../05-scenarios-and-frontend/3-the-eight-scenarios.md).

**The lesson:** *a reset path is a feature and needs its own semantics.* Reusing the business endpoint
looked like reuse and was actually a category error — reset is not an update. And this is the
`/api`–`/demo` split earning its keep on a case Phase 0 never anticipated: rather than weakening the
business rule, the demo got an operation with its own rules, quarantined.

Also: **long-running state accumulates.** Everything worked in a test that created a few orders. The
failure needs *enough* orders, which only an unattended public demo produces.

---

## What these have in common

**The code was correct in every case.** Not one of these is a logic error. A default suited to a
different topology, an autoscaler reading a true metric, a health check that was too honest, and two
correct rules composing badly.

**All four are about resources or time.** Memory during a rollout, CPU during cold start, CPU during a
probe, and state accumulating over hours. None of them is expressible as a unit test.

**Three involve one mechanism interfering with another.** The Deployment controller versus the HPA. The
probe versus the thing it probes. The reset versus the business rule. Each component behaving correctly
in isolation.

**Environment is a first-class variable.** Everything here worked on an 8-core laptop. The 2-vCPU box
is not a smaller version of the same environment — it is a different one, where costs that round to
zero become the dominant term.

---

[← Found by looking](2-found-by-looking.md) · [Next: What this adds up to →](4-what-this-adds-up-to.md)
