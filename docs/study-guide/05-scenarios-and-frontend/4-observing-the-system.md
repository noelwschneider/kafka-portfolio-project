# 5.4 — Observing the system

[← The eight scenarios](3-the-eight-scenarios.md) · [Next: The console →](5-the-console.md)

Phase 5's exit criterion is unusually demanding:

> A reviewer can understand and exercise the system without reading the source code.

That requires showing what is happening *inside* a distributed system to someone who cannot attach a
debugger to it. Two mechanisms do the work — an event projection, and real broker metrics.

---

## The event projection

`EventProjectionConsumer` subscribes to **all eight topics** — four domain, four DLQ — and writes what
it sees into Scenario Service's own `events` table, backing the Event Explorer page.

### It is a separate consumer group, deliberately

> in its own consumer group (`scenario-service-projection`) so it never competes for partitions or
> offsets with a real domain consumer, and never affects delivery to the services that actually own
> the business logic.

This is the log-versus-queue property from
[Chapter 1](../01-design-contract/1-boundaries-and-ownership.md) being cashed in. **Adding an observer
to the system costs the observed nothing.** No producer changed, no existing consumer noticed, no
delivery was diverted. In a queue-based system the same feature would require either a fan-out
exchange configured in advance or a change at every publisher.

It is also why the observer being *down* is harmless: it falls behind and catches up, and the business
never knew.

### The honesty boundary

This is the best single illustration of the project's standards, and it is worth reading in full:

> **Honesty boundary (per the timeline schema's own rule, "do not fabricate these fields"):** this
> consumer records only what a direct Kafka consumer can genuinely observe about a *published* record
> — topic, partition, offset, eventId, correlationId, aggregateId, and the publisher (from the frozen
> topic-ownership table). It does deliberately **not** record a "consumed" phase, a `durationMs`, or a
> `retryCount`: those live inside each service's own `processed_events` row, which this service may
> not read (db-ownership.md's one-owner rule forbids cross-schema queries). Fabricating a
> plausible-looking consumption entry here would violate the "absent, not zero or empty" rule the
> timeline schema states explicitly.

Unpack what is being declined. A UI showing "consumed at 12:04:31.221, 43ms, 0 retries" would look
*better* than one showing only publication. It would also be **made up** — this service cannot observe
consumption, because consumption happens inside another service's transaction against another
service's schema.

Three separate principles converge on the same answer:

- **The ownership boundary is real.** ADR-004 forbids the cross-schema read that would supply the
  data.
- **Absent, not zero.** A missing field is honest; a zero is a claim.
- **Don't claim what you don't provide.** The same rule that bans the phrase "exactly-once."

The cost is a slightly less impressive Event Explorer. The benefit is that everything it shows is
true — and being able to explain *what the page deliberately does not show, and why* is a far stronger
answer than a richer page you cannot defend.

### The publisher lookup

```java
private static final Map<String, String> PRODUCER_BY_TOPIC = Map.of(
        KafkaTopics.ORDERS_EVENTS, "order-service",
        // …
        // A DLQ record is written by the failing *consumer*, not the original publisher
        // (event-catalog.md §2's routing-target note).
        KafkaTopics.INVENTORY_DLQ, "inventory-service",
        // …
);
```

Derived from the frozen topic-ownership table rather than from anything in the record. That is only
possible because *"a service publishes only to its own domain topic"* is a rule — the topic name is
sufficient to name the publisher, with the DLQ rows following the same routing rule
([Chapter 1](../01-design-contract/2-the-event-contract.md)).

A Phase 0 rule that looked like tidiness turning out to make a Phase 5 feature trivial is a recurring
pattern in this project.

### It resolved an open question

`db-ownership.md` §4 originally listed *"The Event Explorer's backing store has no owner yet"* as an
open item — Phase 0 knew the page needed data and deliberately did not guess where it should live.
Phase 5 answered it, and the resolution is recorded in `docs/CHANGELOG-contracts.md`.

**Leaving a question open, marking it, and answering it when there is enough information** is the
practice [Chapter 1](../01-design-contract/4-sequencing-and-deferrals.md) describes, working exactly
as intended.

---

## Consumer lag

```java
/**
 * Real consumer-group lag, read straight from the broker via the admin API — the same computation
 * {@code kafka-consumer-groups.sh --describe} performs (per-partition latest offset minus the
 * group's last committed offset, summed).
 */
```

Not an application counter. Not an estimate. The same number the Kafka CLI reports, obtained the same
way — `AdminClient`, `listOffsets` for the partition ends, `listConsumerGroupOffsets` for the group's
committed positions, subtract and sum.

**Lag is the single most informative number about a Kafka consumer**, because it is the only one that
answers "is this keeping up?" Throughput without lag tells you how fast something is going, not
whether that is fast enough.

Two design choices worth copying:

**It fails soft.**

> Returns `0` if the group has no committed offsets yet [...] or if the broker call fails — this is a
> measurement aid, not a correctness gate, so a transient admin-API hiccup should not fail the
> scenario run.

**It uses its own `AdminClient`**, created from `spring.kafka.bootstrap-servers` and closed via
`DisposableBean`. An admin client is a different thing from a producer or consumer — it talks to the
cluster's metadata and coordinator APIs rather than to topics.

Lag is what makes Scenario 5 legible (it climbs while paused, drains on resume) and what makes
Scenario 8 meaningful in [Chapter 8](../08-observability-and-scaling/README.md) (adding replicas
should drain it faster — until you hit three partitions).

---

## The run timeline

Three kinds of entry, from two different sources:

| `TimelineKind` | Recorded by | Example |
|---|---|---|
| `HTTP` | the scenario harness thread | `POST /api/orders` → 201 |
| `EVENT` | Kafka listener threads, via the projection | `InventoryReserved` on `inventory.events` |
| `STATE_CHANGE` | `OrderStatusWatcher` | `PENDING` → `INVENTORY_RESERVED` |

Interleaving them is what makes the timeline worth looking at. A reviewer sees the 201 return, then
events arriving afterwards, then status changes following the events — the causal chain of an
asynchronous system, in order, with real timestamps.

Two implementation details:

**Sequence assignment is synchronized per run.** Several threads append concurrently — the harness
thread and one or more listener threads — so ordering is imposed explicitly rather than hoped for.

**`detail` is an open map, populated only with what was observed:**

> `detail` is intentionally an open map: per the ScenarioTimelineEntry schema, only fields the caller
> actually observed are included — never a fabricated placeholder.

The same rule as the projection's honesty boundary, applied at field granularity.

---

## System Health

Polled, not streamed — ADR-003's deliberate exception:

> Health data uses ordinary polling of Actuator endpoints, not a stream: it changes rarely and a stale
> health tile is a much smaller problem than a stale order timeline.

Each service exposes `/actuator/health` with liveness and readiness groups. The frontend polls all
five and renders a tile each, alongside consumer states from `/demo/consumers`.

> **Not yet.** Actuator is configured here but is properly the subject of
> [Chapter 8](../08-observability-and-scaling/README.md), which adds metrics, Prometheus, structured
> logging, and the probe endpoints Kubernetes targets. It also covers a CORS trap specific to Actuator
> that made these health tiles fail in the browser while working perfectly under `curl`.

---

## What a reviewer can now do

Against the exit criterion — without reading any source:

- **See the architecture**, rendered from Mermaid on the Architecture page.
- **Create an order** and watch its status change live over SSE.
- **Run any of eight scenarios** and watch an interleaved timeline of HTTP calls, events, and status
  changes as they happen.
- **Browse every event** the system has published, with real topic, partition, and offset.
- **Watch a DLQ record appear** with readable failure metadata.
- **Pause a consumer**, watch lag climb, resume, watch it drain.
- **See what is not shown, and why** — the Event Explorer's missing consumption phase is a documented
  boundary, not a gap.

That last one is the one most portfolio projects cannot offer.

---

[← The eight scenarios](3-the-eight-scenarios.md) · [Next: The console →](5-the-console.md)
