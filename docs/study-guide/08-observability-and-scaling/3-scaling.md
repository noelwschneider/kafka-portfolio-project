# 8.3 — Scaling

[← Metrics](2-metrics.md) · [Next: The autoscaler →](4-the-autoscaler.md)

Phase 10's exit criterion is unusual, and it is the right one:

> The project can demonstrate **why** Kafka consumer groups and Kubernetes scaling are useful.

Not "the project scales." Demonstrate *why the mechanism is useful* — which requires showing where it
helps, and equally where it stops helping.

---

## What scaling means for a consumer

Adding replicas of an HTTP service is uncomplicated: a load balancer spreads requests, and more
instances means more concurrent requests. There is no natural ceiling short of a downstream
bottleneck.

A Kafka consumer is different, and the difference is the whole lesson.

Within a consumer group, **each partition is assigned to exactly one consumer.** That is the mechanism
that gives per-partition ordering — two consumers reading one partition could process its records
concurrently, and ordering would be gone.

So:

| Replicas | Partitions each | Effect |
|---|---|---|
| 1 | 3 | Baseline |
| 2 | 2 + 1 | Roughly 2× parallelism |
| 3 | 1 + 1 + 1 | Maximum useful parallelism |
| 4 | 1 + 1 + 1 + **0** | **The fourth does nothing** |

> a Kafka consumer group can never usefully have more running consumers than partitions — a 4th
> replica would sit idle with no partition assigned.

**The partition count is the parallelism ceiling**, and it was fixed at 3 back in
[Chapter 3](../03-kafka-and-services/1-events-on-the-wire.md).

That is the most useful thing in this chapter. "Just add more pods" is not an answer for a Kafka
consumer; you would have to increase the partition count first — and doing so **changes which
partition existing keys hash to**, breaking per-key ordering across the change.

Which is why the number is worth choosing carefully at design time and is awkward to change later.

---

## Scenario 8

Create many orders quickly and measure what happens. The success condition is a **measurement**, not a
state — the only scenario in the set where that is true.

**Inventory Service is the target**, and the reasoning is specific:

> it's the consumer Scenario 8 is specifically written to stress — it consumes every `OrderCreated` off
> `orders.events` and does a reservation write per order — and it's the exact service Phase 10 already
> scaled by hand and measured (1 vs 2 replicas: consumer lag and throughput both moved). This HPA
> targets **CPU** because that's what visibly saturates first under that scenario's write load, not
> I/O wait or memory.

Three separate observations behind one choice: which service saturates, that scaling it measurably
helps, and *which resource* saturates first. None of them is guessable — all three came from running
it.

The scenario reads **real broker-side lag** through `ConsumerLagService`
([Chapter 5](../05-scenarios-and-frontend/4-observing-the-system.md)), not a self-reported counter. It
is the same number `kafka-consumer-groups.sh --describe` prints.

**Lag is the metric that matters**, because it is the only one that answers *"is this keeping up?"*
Throughput tells you how fast something is going, not whether that is fast enough. Lag climbing means
arrival exceeds processing; lag flat means they match; lag draining means you are catching up.

---

## What Phase 10 actually measured

This is where the honest part of the story is.

> the local `kind` Docker Desktop VM's ~3.8GB ceiling meant **3 replicas of Inventory Service alongside
> the rest of the 8-pod stack pushed the node into CPU/memory contention and Kafka readiness-probe
> flapping before any scenario load was even applied.**

So Phase 10 could demonstrate 1 → 2 replicas and could not reach 3 on the development laptop. Not
because 3 is wrong — it is exactly right, matching the partition count — but because **the hardware ran
out before the architecture did.**

That is recorded rather than hidden, and it is a better outcome than a clean graph would have been.
Two things came out of it that a successful run would not have produced:

**A concrete resource budget.** The 8-pod baseline stack fits inside 3.825GiB. That number is what
Sprint 2's deployment sizing decision cites when choosing a production box — a measurement, not an
estimate.

**The probe finding.** *"Kafka readiness-probe flapping"* under CPU contention identified the
`kafka-broker-api-versions.sh` health check — which starts a JVM per invocation
([Chapter 7](../07-containers-and-kubernetes/1-containers-and-compose.md)) — as a real cost on a
constrained node. That became blocking work item T1 in
[Chapter 9](../09-production/README.md), before it could take down the public demo.

**A capacity limit found in testing is a capacity limit not found in production.** Phase 10's inability
to reach 3 replicas is the reason Chapter 9's production box was sized and tuned correctly on the first
attempt.

---

## Demonstrating it by hand

```bash
kubectl scale deployment/inventory-service --replicas=2
kubectl get pods -n orderfulfillment -w
```

Then run Scenario 8 and watch:

- **`kubectl get pods`** — a new pod, then a **rebalance** as the group reassigns partitions.
- **Consumer lag** — climbing during the burst, draining faster with two consumers than one.
- **The Grafana dashboard** — CPU across both replicas, and per-replica consumer lag.
- **Order completion** — the same total work finishing sooner.

The rebalance is worth watching specifically. Adding a consumer to a group triggers a partition
reassignment, and **processing pauses** while it happens. At small scale that is a blip; at large scale
it is why people care about cooperative rebalancing protocols. It is also one of the ordinary causes of
duplicate delivery ([Chapter 4](../04-reliability/README.md)) — a partition's uncommitted records get
reprocessed by their new owner, and the idempotency ledger absorbs it.

So a scale-up exercises the reliability machinery as a side effect. Watching lag drain while
`processed_events` quietly suppresses redeliveries is two chapters demonstrating themselves at once.

---

## What scaling does not fix

Worth being able to say, because it is the follow-up question.

**More replicas than partitions does nothing.** The ceiling is 3.

**Order Service does not scale the same way.** Its status writes take a per-order pessimistic row lock
([Chapter 4](../04-reliability/4-out-of-order-transitions.md)). That serializes *per order*, so
different orders never contend and replicas still help — but it is a different profile from Inventory's
CPU-bound write loop, and it would need its own measurement rather than an assumption.

**PostgreSQL does not scale here at all.** One instance, one PVC,
`strategy: Recreate` ([Chapter 7](../07-containers-and-kubernetes/2-kubernetes-manifests.md)). Every
service's replicas share it. Past a certain load the database is the bottleneck and no amount of pod
scaling helps — which is the usual shape of real systems and worth naming rather than implying that
horizontal scaling is unbounded.

**Kafka does not scale here either.** One broker, replication factor 1. Deliberate scope
(`project-overview.md` rules out "full production Kafka operations").

---

[← Metrics](2-metrics.md) · [Next: The autoscaler →](4-the-autoscaler.md)
