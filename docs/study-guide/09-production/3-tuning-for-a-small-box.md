# 9.3 — Tuning for a small box

[← The production overlay](2-the-production-overlay.md) · [Next: The outage →](4-the-outage.md)

Four changes, all blocking rather than optional, all traceable to the same measurement: **on 2 vCPUs,
cold start is the pressure point.**

---

## Why these were blocking

Sprint 2's plan is unusually firm about it:

> All four are **blocking, not optional** — undiagnosed, they'd reproduce the same probe-flapping
> problem Phase 10 found, this time on the public demo.

Phase 10 already produced the failure these prevent, on a laptop, where it cost an afternoon. The
tuning exists so it does not happen again in front of a visitor.

Note also the discipline about *where* each change lives — the base manifests, or the overlay:

> Everything here is specific to the 2-vCPU / 4 GB demo box and deliberately does **not** go into the
> base manifests: on an 8-core development machine these numbers would only make failures **slower to
> surface.**

Which is the right test for an environment-specific setting. A generous timeout on a fast machine does
not help; it delays the moment you learn something is wrong.

---

## T1 — the Kafka probe

The one change that went into the **base** manifests:

> The one tuning change that IS a strict improvement everywhere — Kafka's readiness probe no longer
> spawning a JVM per check — lives in `../03-kafka.yaml` instead.

From [section 1](1-the-platform-decision.md): `kafka-broker-api-versions.sh` starts a JVM inside the
broker container **on every check**. Under CPU contention it times out, flaps the broker
Ready/NotReady, and empties the Service endpoints — *"a probe whose own cost causes the failure it is
meant to detect is not a probe."*

The replacement is a `tcpSocket` check, and the ADR is honest about what that gives up:

> The honest cost is that a TCP accept proves the listener is **bound**, not that the broker will
> answer a metadata request — a weaker signal, bought by removing the probe's own CPU cost.

**A weaker signal that is always available beats a stronger signal that fails under the exact
conditions you need it.** Not a free win, and the right trade — which is why it belongs in the base
manifests rather than the overlay.

> **Primer — [Kubernetes: health probes](../technology/kubernetes/probes.md)**
> Probe types and their costs, why an `exec` probe is the expensive one, and how probes amplify
> load-induced failure.

---

## T2 — explicit heap caps

```
JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=60"
```

> Left to itself the JVM takes **25% of the container limit** (160 MiB of the 640 MiB limit here) and
> Kafka takes a **flat 1 GB regardless of its limit.** Neither number is chosen with any knowledge of
> this workload. `MaxRAMPercentage=60` gives each service ~384 MiB of heap and leaves ~256 MiB for
> metaspace, code cache, thread stacks and direct buffers — **which is the part that gets a container
> OOMKilled when people set the heap to the limit.**

Three separate facts, each worth knowing independently.

**The JVM's container-aware default is 25%**, which on a 640 MiB limit is 160 MiB of heap and 480 MiB
unused. Not dangerous — wasteful, on a box where every megabyte is allocated.

**Kafka's start script ignores the container limit entirely** and asks for a flat 1 GB. On a 4 GB box
with five other JVMs, that is a quarter of the machine claimed by a default.

**Heap is not the whole JVM.** Metaspace, code cache, thread stacks, and direct byte buffers sit
*outside* it. Setting the heap to the container limit guarantees an OOM kill — and the kill is
delivered by the kernel to the container, so it looks like a crash rather than a memory error.
[Chapter 7](../07-containers-and-kubernetes/3-probes-and-resources.md) flagged this as "not yet"; here
it is, at 60%.

`MaxRAMPercentage` rather than a fixed `-Xmx` is the right form: it stays correct if the limit
changes, and one value works for every service.

---

## T3 — a startup probe

```yaml
startupProbe:
  # …allows up to 5 minutes
```

> **Startup is the pressure point, not steady state.** Five JVMs and a broker starting at once on 2
> vCPUs is exactly the contention Phase 10 measured at 270-406% CPU on an 8-core machine. A
> startupProbe is the right tool: while it is running, the liveness and readiness probes are **not
> evaluated at all**, so a slow cold start cannot be read as a failure and cannot restart a pod that
> is merely waiting for a CPU slice. It allows up to 5 minutes to come up, then hands over to the
> normal probes — which keep their base semantics but with more headroom.

The failure this prevents is the crash loop from
[Chapter 7](../07-containers-and-kubernetes/3-probes-and-resources.md), with a twist that makes it
worse: five pods starting together contend for CPU, all start slowly, all miss their liveness probes,
all get restarted — and the restarts start five more JVMs, deepening the contention. **A death
spiral triggered by the health checks.**

The startup probe breaks it by suspending the other two entirely until the application is up.

The alternative — a very long `initialDelaySeconds` on liveness — would work for startup and leave
liveness permanently slow to detect a genuinely wedged process. The startup probe decouples the two:
**a generous boot budget *and* a tight liveness check afterwards**, instead of trading one for the
other.

Note the phrasing: *"which keep their base semantics but with more headroom."* The readiness group
still includes `db`; the liveness check is still the same endpoint. Only the timings are relaxed.

---

## T4 — scenario timeouts

Scenario timeouts are Spring properties rather than probe settings, so they travel via
`SPRING_PROFILES_ACTIVE=production` and live in Scenario Service's `application-production.yml`.

The problem is real: scenario runners wait for observed outcomes
([Chapter 5](../05-scenarios-and-frontend/1-the-scenario-service.md)) with bounded polls. Those bounds
were tuned on 8-core hardware, and a scenario that times out on a slower box reports a **failure** for
work that was merely slow.

**Timeouts calibrated on fast hardware are a correctness problem on slow hardware.** Not a performance
problem — the scenario reports the wrong answer.

The same profile also enables the idle auto-reset from
[section 2](2-the-production-overlay.md). One environment variable, two production behaviors, both
about the box being unattended.

---

## T5 — `maxSurge: 0`

This one is [section 4](4-the-outage.md)'s subject, and it arrived *after* an outage rather than
before. It lives in the same file for a stated reason:

> This is a **Deployment strategy, not a resource number**, so it is unconditionally correct for the
> demo box regardless of future capacity changes — it does not belong in the same conditional as the
> heap caps and probe timing above, but lives alongside them here per this directory's existing
> convention of one file for CX23-only Deployment patches.

Noting that a change does not really belong with its neighbours, and putting it there anyway for
consistency, with the reasoning recorded. That is the honest way to make a filing decision you are not
fully happy with.

---

## The shape of all of this

Every one of T1–T4 follows the same pattern:

1. **A measurement from Phase 10** — CPU at 270–406%, probe flapping, the 3.825 GiB stack.
2. **A specific mechanism** it predicts will fail on 2 vCPUs.
3. **A change with a stated cost**, not a free win.

| | Prevents | Costs |
|---|---|---|
| T1 TCP probe | Probe-induced broker flapping | A weaker readiness signal |
| T2 heap caps | OOM kills; wasted memory | A number to revisit if limits change |
| T3 startup probe | Cold-start crash spiral | Up to 5 minutes before liveness applies |
| T4 timeouts | False scenario failures | Slower failure detection in scenarios |

**Naming what each change costs** is what separates tuning from cargo-culting. A change with no stated
cost is usually one whose cost has not been found yet.

---

[← The production overlay](2-the-production-overlay.md) · [Next: The outage →](4-the-outage.md)
