# Kubernetes: health probes

*Referenced from [Chapter 7.3 — Probes and resources](../../07-containers-and-kubernetes/3-probes-and-resources.md).*

Three probes, asking three different questions. Getting them confused causes restart loops and outages
in roughly equal measure.

---

## The three questions

| Probe | Question | Failure means |
|---|---|---|
| **Startup** | Has it finished booting? | Keep waiting; suppress the other probes |
| **Readiness** | Should traffic go here **right now**? | Remove from Service endpoints — **no restart** |
| **Liveness** | Is this broken beyond recovery? | **Kill and restart the container** |

The distinction that matters most is readiness versus liveness, and the test for it is simple:

> **Would restarting this container fix the problem?**

If yes — a deadlock, an unrecoverable internal state, a wedged thread pool — it is a liveness concern.
If no — a dependency is down, a cache is warming, a queue is backed up — it is a **readiness** concern.

## Why the distinction is not academic

Consider a service whose database is briefly unreachable, with the dependency check wired into
**liveness**:

1. The database blips. Liveness fails.
2. Kubernetes restarts every pod.
3. The restarted pods still cannot reach the database. Liveness fails again.
4. `CrashLoopBackOff`. Now the service is down *and* thrashing, and when the database returns, every
   pod is in a backoff window.

**Restarting a healthy pod does not fix a sick dependency.** It converts a partial outage into a total
one, plus a restart storm at exactly the moment the dependency is recovering.

Wired into **readiness** instead: pods leave the Service endpoints, traffic stops being routed to them,
nothing restarts, and when the database returns they become ready again on the next probe. The
degradation is proportional to the fault.

**The safe default: liveness checks only the process itself. Readiness checks the dependencies.**

## Configuration

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

| Field | Meaning |
|---|---|
| `initialDelaySeconds` | Wait this long after container start before probing at all |
| `periodSeconds` | How often |
| `timeoutSeconds` | How long one probe may take before counting as failed |
| `failureThreshold` | Consecutive failures before acting |
| `successThreshold` | Consecutive successes to recover (liveness must be 1) |

Time to action is roughly `initialDelay + (period × failureThreshold)`.

**Liveness should be slower and more forgiving than readiness**, in every dimension. Readiness is
cheap to get wrong — a pod briefly leaves the load balancer. Liveness is expensive — a restart, a lost
in-flight request, a cold JVM. Make it require more evidence.

**A too-short `initialDelaySeconds` on liveness is the classic self-inflicted crash loop.** A JVM that
takes 40 seconds to start, probed from second 10 with a threshold of 3, is killed at second 25 and
never starts successfully. It looks like the application is broken.

## Probe types

**`httpGet`** — a 2xx or 3xx passes. The usual choice.

**`tcpSocket`** — can a connection be opened? Cheap, and the right answer for non-HTTP services or for
anything where a heavier check is itself a problem.

**`exec`** — run a command in the container; exit 0 passes. The most flexible and the most expensive:
it forks a process on **every probe, on every pod, forever**. An `exec` probe that starts a JVM is a
constant CPU cost that will find you on a small node.

## Startup probes

For applications with a slow but bounded start, a startup probe is better than a long
`initialDelaySeconds` on liveness:

```yaml
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: 8081 }
  failureThreshold: 30
  periodSeconds: 5          # allows up to 150s to start
```

While a startup probe is failing, readiness and liveness are **not evaluated at all**. Once it
succeeds, it never runs again and the others take over.

This decouples "may take a while to boot" from "must respond quickly once running" — so you get a
generous startup budget *and* a tight liveness check afterwards, instead of trading one for the other.

## Spring Boot specifics

Actuator provides the two endpoints directly:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true      # /actuator/health/liveness and /actuator/health/readiness
```

By default, **readiness reflects only Spring's own `readinessState`** — application-context lifecycle,
not your dependencies. Health indicators that Spring auto-registers (`db`, `diskSpace`, …) appear in
`/actuator/health` but not in the readiness group unless you add them:

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,db
```

Two things to check rather than assume:

**Which indicators actually exist.** `GET /actuator/health` with details enabled lists them. Not every
dependency has one — Spring Kafka, for instance, does not register a broker health indicator by
default, so a readiness group cannot include what is not there.

**That the group means what you think.** Adding an indicator to readiness makes that dependency's
failure pull the pod out of rotation. That is usually right for a database and usually wrong for a
non-critical downstream service, whose outage should degrade a feature rather than remove the pod.

## The costs

**Every probe is real traffic.** Five pods × two probes × every few seconds is a constant load floor
that shows up on a small node.

**Probes can lie under load.** A pod that is merely slow will fail `timeoutSeconds` and be restarted —
and the restart removes capacity, making everything else slower. Liveness probes are a genuine
amplifier of load-induced failure, which is the strongest argument for keeping them minimal.

**Readiness gates rollouts.** A rolling update waits for new pods to become ready. Readiness that is
too strict makes a deploy hang; readiness that is too loose sends traffic to a pod that cannot serve
it.
