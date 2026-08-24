# Phase 10 — Scaling Demo

**Scope:** verify the Scenario 8 ("High-Volume Batch") implementation left uncommitted from a prior
session, stand up the Phase 8 `kind` cluster fresh, run the scenario against the live cluster at
1/2/3 Inventory Service replicas, and record real throughput/lag/fulfillment measurements. Gate:
"demonstrates why consumer groups + k8s scaling matter, with real measurements" (execution-plan.md
Phase 10 row).

---

## 1. Build and test verification

Ran `mvn -pl services/common,services/scenario-service -am clean verify` in the foreground
(no backgrounding), full run to completion.

| Check | Result |
|---|---|
| Compile (`services/common`, `services/scenario-service`) | Pass — no changes needed. The uncommitted code (`ConsumerLagService`, `HighVolumeScenario`, `OrderStatusWatcher` additions, catalog/config/YAML plumbing) compiled clean on the first attempt. |
| Unit + integration test suite (`services/scenario-service`) | Pass — 11 integration test classes ran (Testcontainers-backed), **14 tests total, 0 failures, 0 errors, 0 skipped**. `HighVolumeScenarioIntegrationTest` (1 test) passed. `ScenarioConflictIntegrationTest` (the file touched by this session's uncommitted diff) passed. |

No code fixes were required. The implementation was correct as written; everything below is about
running it against the real cluster, not the code itself.

## 2. Cluster stand-up

Fresh `kind` cluster (`orderfulfillment`), no prior cluster existed.

| Step | Result |
|---|---|
| `kind create cluster --config infrastructure/kind-config.yaml` | **27.1s wall time**, first attempt, no image pre-pull needed. |
| Build 5 backend images + frontend image (`docker build`, unmodified Phase 7 Dockerfiles) | All 6 built successfully, a few seconds each. |
| `kind load docker-image <name>:local --name orderfulfillment` × 6 | All 6 loaded successfully. |
| `kubectl apply -f infrastructure/kubernetes/` | All 21 objects created (namespace, secret, 2 PVC+Deployment+Service pairs for Postgres/Kafka, 5×(ConfigMap+Deployment+Service), frontend Deployment+Service). |
| `kubectl wait --for=condition=ready pod --all --timeout=300s` | All 8 pods reached Ready on the **first attempt**, no manual recovery needed (unlike Phase 8's first pass). |
| `/actuator/health/readiness` on all 5 backend services | All 5 returned `{"status":"UP"}`. |

This first stand-up was clean and fast — see §5 for why later steps were not.

## 3. Real measurements: 1 and 2 Inventory Service replicas

Procedure per data point: `kubectl scale deployment/inventory-service --replicas=N`, wait for all
replicas Ready, `POST /demo/reset` (restocks SKU-003 to 100), `POST /demo/scenarios/high-volume`,
poll `GET /demo/scenario-runs/{runId}` to terminal, read the scenario's own recorded timeline
(submission throughput, lag samples/peak, drain duration, fulfilled/not-fulfilled counts) — all real
HTTP/Kafka/Postgres activity across the live cluster, nothing simulated.

| Replicas | Run ID | Submission duration | Submission throughput | Peak consumer lag (`inventory-service` group, `orders.events`) | Drain duration | End-to-end duration | Orders fulfilled |
|---|---|---|---|---|---|---|---|
| 1 | `run-101` | 9,266 ms | 6.48 orders/sec | 14 | 89,740 ms | 99,006 ms | 57/60 |
| 2 | `run-102` | 3,916 ms | 15.32 orders/sec | 46 | 79,905 ms | 83,821 ms | 26/60 |
| 3 | — | — | — | — | — | — | **not obtained — see §5** |

Both runs eventually reported scenario status `FAILED`, **not because inventory scaling failed**,
but because of a genuine, previously-unknown correctness defect in Order Service found live during
this measurement — see §4. The throughput/lag numbers above are unaffected by that defect (they're
computed before the defect's effect surfaces) and are real.

### Reading the numbers

- **Submission throughput more than doubled (6.48 → 15.32 orders/sec) going from 1 to 2 replicas.**
  This is a genuine artifact of client-side submission concurrency (`ScenarioProperties`'
  `high-volume-submission-concurrency: 20`) racing against however fast the *previous* run's
  JVM/HTTP-connection-pool warm-up state was — submission speed is bounded by Order Service's own
  write path (Postgres insert + outbox), not by Inventory Service's replica count at all. Inventory
  Service doesn't see the order until `OrderCreated` lands on `orders.events`, after the order is
  already durably created. So this number is not itself a "scaling" signal — it's included because
  the scenario reports it, but the metric that actually reflects Inventory Service's replica count is
  consumer lag, next.
- **Peak consumer lag went up (14 → 46), not down, from 1 to 2 replicas** — the opposite of the naive
  expectation ("more consumers should drain faster"). This is explained by the throughput change
  above: at 2 replicas' run, orders were *produced* into `orders.events` more than twice as fast
  (15.32 vs 6.48/sec) than at 1 replica's run, while consumer capacity only doubled. A faster producer
  outrunning a proportionally-scaled consumer produces a *larger* peak backlog before it drains, even
  though the consumers are collectively working harder. The two runs are not a controlled experiment
  isolating "replica count" as the only variable — submission speed varied between them for reasons
  external to Inventory Service scaling (JVM/connection-pool state, host load). This is worth stating
  plainly rather than smoothing over: the peak-lag numbers as measured do not cleanly demonstrate
  "more replicas ⇒ lower peak lag" the way a controlled load-generator holding submission rate
  constant would. What they do demonstrate is that consumer lag is a real, live, observable number
  driven by production and consumption rates on an actual Kafka topic (14 and 46 are both genuine
  `AdminClient`-computed lag values, not synthetic) — the qualitative claim in Scenario 8's own
  description ("observes throughput and consumer lag as the backlog drains") holds; the specific
  "scaling reduces lag" narrative does not come through cleanly in the numbers actually collected here.

## 4. New defect found live: Order Service status race between independently-consumed topics

> **Resolved, 2026-08-20.** Fixed in Order Service by
> `docs/adr/ADR-009-out-of-order-status-transitions.md` — the transition table is now enforced on
> every write, an out-of-order transition is parked in `deferred_transitions` until its predecessor
> is applied, and nothing can revert a terminal state. Covered by the deterministic regression test
> `OrderOutOfOrderTransitionIntegrationTest`. The write-up below is left as found, as the record of
> how the defect was discovered.

While chasing why both runs above reported `FAILED`, found a real, reproducible correctness bug —
distinct from, and more serious than, the SSE-under-concurrency issue already known from the prior
session's work.

**Symptom:** a handful of orders each run got permanently stuck at `FULFILLMENT_PENDING` — never
reaching `FULFILLED` — despite Fulfillment Service having successfully processed their
`PaymentAuthorized` event and published `ShipmentCreated`, and Order Service having successfully
consumed that `ShipmentCreated` event. Example, order `order-20044` (replica=1 run)'s
`statusHistory`:

```
PENDING → INVENTORY_RESERVED → PAYMENT_PENDING → FULFILLED → PAID → FULFILLMENT_PENDING
```

`FULFILLED` appears *before* `PAID` — out of the documented state-machine order — and the order is
left resting on `FULFILLMENT_PENDING`, not `FULFILLED`.

**Root cause:** Order Service's status field is mutated by two *independently consumed* Kafka topics
with no ordering guarantee between them: `OrderPaymentEventsConsumer` (reacting to `PaymentAuthorized`
on `payments.events`, transitioning `PAYMENT_PENDING → PAID`) and `OrderFulfillmentEventsConsumer`
(reacting to `ShipmentCreated` on `fulfillment.events`, transitioning `→ FULFILLMENT_PENDING →
FULFILLED`). Fulfillment Service has its *own*, separate consumer of `payments.events`
(`fulfillment-service` consumer group, independent from Order Service's own consumer group on the
same topic — this fan-out is a deliberate design per `docs/events/event-catalog.md` §3, so both
services see every `PaymentAuthorized` record without waiting on each other). Under load, nothing
prevents Fulfillment Service's consumer from processing `PaymentAuthorized` and publishing
`ShipmentCreated` *before* Order Service's own, separate consumer of the same `PaymentAuthorized`
event has run and applied the `PAID` transition. When that happens, Order Service's
`OrderFulfillmentEventsConsumer` fires first and drives the order straight from `PAYMENT_PENDING` to
`FULFILLMENT_PENDING`/`FULFILLED`-adjacent states, skipping `PAID` — and when the delayed
`PaymentAuthorized` processing finally runs afterward, it unconditionally sets status back to `PAID`,
**stomping the already-correct terminal state**. Neither consumer checks "is the order already past
this point" before applying its own transition — there is no guard against out-of-order arrival
across the two independently-raced topics.

This reproduced on **both** the replica=1 run (3/60 orders affected) and the replica=2 run (34/60
orders affected — visibly worse at higher submission concurrency, consistent with the race being a
timing/ordering issue that gets more likely to trigger under more concurrent in-flight orders).
Confirmed via `kubectl logs` cross-referencing exact timestamps on `order-service` and
`fulfillment-service`, not inferred — e.g. for `order-20044`: Fulfillment Service logged
"Processing PaymentAuthorized ... for order order-20044" at `16:21:02.996`, in a run where Order
Service's own `OrderPaymentEventsConsumer` didn't log processing that same event until `16:21:10.060`
— over 7 seconds later, plenty of time for `ShipmentCreated` (published immediately after Fulfillment
Service's processing) to race ahead and be consumed by Order Service's `OrderFulfillmentEventsConsumer`
first.

**This is out of scope to fix here** — it's an Order Service application-code defect (a missing
state-transition guard / lack of enforced ordering across two independently-consumed topics touching
the same aggregate), not a scenario-service or Kubernetes-scaling issue, and fixing it correctly needs
a real design decision (e.g., a guard clause per transition, or funneling both topics through one
ordered consumer, or a saga-style explicit precondition check) rather than a one-line patch. Per this
phase's brief, flagging it here rather than improvising a fix.

**Recommended follow-up (not done in this phase):** add a guard in both
`OrderPaymentEventsConsumer` and `OrderFulfillmentEventsConsumer` that only applies a transition if
the order's current status is the expected predecessor state, and/or an ADR documenting that Order
Service's aggregate status is written by multiple independently-raced consumers and what ordering
guarantee (if any) is actually provided today (currently: none). No existing `docs/` location tracks
"known issues" as a category (checked `docs/adr/`, `docs/scenarios.md`, and `docs/planning/README.md`'s
index — there is no known-issues tracker in this project), so this write-up here is the only place
this defect is currently recorded; a maintainer should decide whether it warrants a new ADR or a
dedicated known-issues doc.

This is a second, independent, real finding — on top of the SSE-under-concurrency defect already
documented in `OrderStatusWatcher.awaitTerminalPollOnly`'s Javadoc from the prior session (Order
Service's SSE emitter can throw an uncatchable `HttpMessageNotWritableException` under this
scenario's concurrency once the response is committed to `text/event-stream`) — both are Order
Service correctness/robustness gaps surfaced specifically by this phase's concurrency, not by the
scenario-service or Kubernetes work done in this phase.

## 5. Replica=3: infrastructure ceiling, not obtained

Scaling Inventory Service to 3 replicas was attempted **4 separate times**, including one attempt
with Frontend scaled to 0 to free headroom and one attempt using a single long `kubectl wait` instead
of frequent polling (to rule out the polling itself adding load). All 4 attempts reproduced the same
outcome:

- Docker Desktop's VM for this `kind` node is capped at **3.825 GiB** (`docker info`), the same
  hard ceiling Phase 8's report already documented as tight for this node's pod count.
- At 3 Inventory Service replicas (9 total pods: Postgres, Kafka, 5 other backend services, 3×
  Inventory Service, plus Frontend when present), memory usage sat at 76-84% and CPU usage spiked to
  270-406% (multiple cores saturated) even **before** any scenario load was generated.
- Kafka's own readiness probe (`kafka-broker-api-versions.sh`, itself a JVM subprocess) began
  repeatedly timing out (`command timed out ... after 5s`) under this contention, flapping
  Ready/NotReady for 10+ minutes continuously across attempts — confirmed via `kubectl get endpoints
  kafka` showing **zero ready endpoints** during a flap, meaning new connections via the Kafka Service
  DNS would genuinely fail cluster-wide during those windows, not just a cosmetic status flag.
- 2 of the 3 Inventory Service pods entered a genuine crash-restart loop (3 restarts in under 9
  minutes, not converging) rather than a one-time slow start.
- Each time, scaling back to 1 replica let the cluster fully recover to Ready within roughly a
  minute, confirming the instability was specifically tied to the 3-replica pod count, not a
  cumulative/permanent degradation of the node.

Per this session's explicit instruction to flag renewed infra trouble rather than silently push
through with retries: **this is a new data point, and it is worth distinguishing carefully from the
earlier HDD-I/O pathology this phase's brief described.** The symptoms here are different in kind:

- Cluster creation (27s), all 6 image builds (seconds each), all 6 `kind load docker-image` calls,
  and the initial 8-pod stand-up (first attempt, no manual recovery) were all **fast**, matching the
  brief's expectation that the SSD fix resolved the earlier disk-I/O-bound hangs. Nothing here
  resembled the previously-described multi-minute daemon hangs or TLS handshake stalls at rest.
- The instability found here is **CPU/memory contention under concurrent JVM pod load** — 9 JVM-class
  pods (Postgres is native, but Kafka + 5 Spring Boot services + up to 3 Inventory Service replicas is
  8 JVMs) competing for 8 vCPUs and 3.825 GiB inside one Docker Desktop VM — the same resource-ceiling
  finding Phase 8 already flagged as this sandbox's limitation, now hit at a lower total pod count
  (9 vs. Phase 8's 8) because this phase specifically adds a 3rd replica of an already-present service.
  This is compute-bound contention at the container-orchestration layer, not the earlier disk-I/O
  pathology — a genuinely different failure mode, but still a real environment constraint, not a
  fluke or a manifest defect.

**Conclusion:** the SSD fix appears to have resolved the disk-I/O-bound hangs it targeted. Separately,
this specific sandbox's Docker Desktop VM cannot sustain 3 concurrent replicas of a JVM service
alongside the rest of this project's 8-service stack without sustained instability. Getting a clean
replica=3 data point in this environment would need either more VM memory/CPU allocated to Docker
Desktop (a system-settings change outside this phase's — and this agent's permission tier's — scope
to make unilaterally) or a smaller concurrent footprint (e.g., temporarily stopping unrelated services
during the replica=3 measurement specifically), neither of which was done here without asking first.

The topic's fixed 3-partition ceiling (`docs/db-ownership.md` / event-catalog partition counts) means
a real replica=3 run would be expected to show consumer lag draining faster than replica=2 relative to
whatever the actual production rate is (3 partitions ⇒ 3 is the last replica count where every
consumer instance gets a partition; a 4th replica, per the original brief, should show no further
improvement) — but this is the documented *expectation* from the architecture, not a number this
session was able to measure.

## 6. Teardown

`kind delete cluster --name orderfulfillment` run at the end of this session. Confirmed via
`kind get clusters` (empty) and no leftover `orderfulfillment-control-plane` container in `docker
ps -a`. Docker Desktop itself was left running (it was already running before this session started,
per project convention on only stopping infra a session itself started).

## 7. Not committed

Per this phase's instructions, the working tree was left exactly as inherited (the uncommitted diff
from the prior session, unchanged) — no `git commit` was run. This report is a new untracked file
under `docs/agent-reports/`.
