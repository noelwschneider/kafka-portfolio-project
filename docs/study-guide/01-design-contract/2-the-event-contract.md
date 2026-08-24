# 1.2 — The event contract

[← Boundaries and ownership](1-boundaries-and-ownership.md) · [Next: State and API contracts →](3-state-and-api-contracts.md)

`docs/events/event-catalog.md` is the most valuable document in this project. Everything that crosses
a service boundary is defined in it, and nothing crosses a boundary that isn't.

---

## The problem: a consumer gets bytes

Kafka does not know or care what you put in a record. A record is a key (bytes), a value (bytes), a
timestamp, and some headers. That is the entire model.

So when Inventory Service pulls a record off `orders.events`, it holds a byte array. Before it can do
anything at all it must answer:

- **What is this?** Is it an `OrderCreated`, or something else? A topic can carry more than one event
  type, and this one does.
- **What shape is the inside?** Which fields exist, which are required, what types are they?
- **Have I seen it before?** At-least-once delivery means the answer is sometimes yes, and processing
  it twice must not reserve stock twice.
- **What is it about?** Which order does this concern?
- **Where did it come from?** When something goes wrong across five services, which chain of events
  does this belong to?

None of that is in Kafka. All of it has to be in the record you wrote.

The naive approach is to serialize the domain payload directly — `{"orderId": "...", "items": [...]}`
— and infer the rest. It works for exactly one event type. The moment a topic carries two, the
consumer is guessing from field presence, which is the kind of code that works until someone adds an
optional field.

## The technology: an envelope

The standard answer is an **envelope**: a fixed outer structure, identical for every event in the
system, carrying a variable inner `payload`. The consumer deserializes the envelope first — which it
can always do, because the envelope never changes — reads the type from it, and only then interprets
the payload accordingly.

This is the same idea as an HTTP message (fixed headers, variable body) or an email (fixed headers,
variable content), and it buys the same thing: infrastructure can route, deduplicate, trace, and log
a message without understanding its contents.

---

## This project's envelope

```json
{
  "eventId": "0c7c3acd-8b3b-45fd-ae4a-b8c73b5a419e",
  "eventType": "OrderCreated",
  "eventVersion": 1,
  "occurredAt": "2026-08-07T20:31:04.220Z",
  "correlationId": "d89512f7-b544-4170-b66b-2e93f475ea8f",
  "aggregateId": "order-21873",
  "payload": {
    "orderId": "order-21873",
    "customerId": "demo-customer",
    "items": [{ "sku": "SKU-001", "quantity": 2 }]
  }
}
```

Six envelope fields. Each answers one of the questions above, and each is worth understanding
individually — this is the structure the entire rest of the system is built on.

### `eventId` — a UUID, unique per published record

The **idempotency key**. A consumer records the `eventId`s it has processed and skips ones it has
seen ([Chapter 4](../04-reliability/README.md)).

The subtle part, and the part worth getting right in your head: it is unique per *published record*,
not per *delivery*. A redelivered record carries the **same** `eventId` — that is exactly what makes
deduplication possible, and it is exactly what Scenario 4 exploits when it republishes a record
verbatim to prove the consumer is idempotent. If a duplicate got a fresh `eventId`, no consumer could
tell it apart from a genuinely new event, and nothing downstream could save you.

### `eventType` — one of eight frozen strings

Routing and interpretation. `OrderCreated`, `InventoryReserved`, `InventoryReservationFailed`,
`InventoryReleased`, `PaymentRequested`, `PaymentAuthorized`, `PaymentRejected`, `ShipmentCreated`.

All past tense. Not a stylistic preference: **an event is a statement of fact about something that
already happened**, and the naming enforces the mental model. `ReserveInventory` would be a command —
a request that the recipient may decline. `InventoryReserved` is a report that cannot be declined,
only reacted to. Commands imply a boss; events imply peers. This system has no boss.

### `eventVersion` — an integer, starting at 1

The escape hatch for changing a payload's shape later without breaking consumers that have not caught
up. The rule (§5 of the catalog):

- **Additive, optional field** → no bump. Consumers must ignore unknown fields, so deserialization is
  configured to tolerate them.
- **New required field, removed field, renamed field, or changed semantics** → bump the version for
  that event type only, and document what changed.
- A consumer receiving a version it does not know must treat it as a **non-retryable** failure and
  dead-letter the record. Retrying cannot fix a schema you do not understand — a distinction
  [Chapter 4](../04-reliability/README.md) builds real machinery around.

The catalog is honest about the status of this field: *"`eventVersion` exists in v1 mainly so that
this rule can be stated and demonstrated; no event is expected to reach version 2."* That is the right
call and the right way to record it. A versioning scheme you have thought through and not yet needed
is cheap; retrofitting one onto a live topic is not.

### `occurredAt` — RFC 3339, UTC, milliseconds

When the publishing service **decided the event happened** — not when Kafka wrote it, and not when a
consumer read it.

The distinction is easy to wave past and matters under exactly the conditions this project
demonstrates. With the outbox ([Chapter 6](../06-outbox/README.md)), an event can be written to a database
minutes before it reaches Kafka. With a paused consumer (Scenario 5), it can be read an hour after
that. If `occurredAt` meant "when it was published," a timeline built from these events would be a
timeline of infrastructure delays rather than of business facts.

### `correlationId` — a UUID, constant across one workflow

The thread that ties everything together. Generated once by whoever starts a workflow — Order Service
on `POST /api/orders`, or Scenario Service at the start of a run — and **copied by every consumer onto
every event it publishes in reaction**.

That copying rule is the whole mechanism. Order Service generates a correlation ID and puts it on
`OrderCreated`. Inventory Service consumes that, and when it publishes `InventoryReserved` it does not
generate a new one — it carries the same one forward. So does Payment Service, and Fulfillment. One
order's entire event chain, across four services, shares one identifier.

The same identifier also lands in every log line those services write while handling the event
([Chapter 8](../08-observability-and-scaling/README.md)), which turns "what happened to order 21873" from an
archaeology exercise into a filter.

ADR-001 lists this as a consequence rather than a feature: *"Debugging spans process boundaries, which
is why correlation IDs are a required envelope field rather than a nice-to-have."*

### `aggregateId` — the thing the event is about

For every event in this system, the `orderId`. It is also the Kafka record key, which is where it
stops being bookkeeping and starts being load-bearing — see below.

"Aggregate" is domain-driven-design vocabulary for the entity that owns a consistency boundary. This
project has exactly one, which keeps the concept simple: everything is about an order.

### `payload` — event-specific, never null

Use `{}` rather than omitting it. One deliberate redundancy is allowed: `orderId` appears in both
`aggregateId` and inside each payload, *"because payload consumers should not have to understand the
envelope's aggregate convention to find the order."*

That is a small decision with a good justification, and the sort of thing worth being able to defend:
a rule that is 95% consistent with one documented exception is better than a rule that is 100%
consistent and forces every consumer to learn a convention.

---

## Topics and keys

### The technology: partitions are the unit of ordering

A Kafka topic is split into **partitions**, each an independent, ordered, append-only sequence. Which
partition a record lands in is decided by its **key**: same key, same partition, always.

> Kafka guarantees ordering **within a single partition**. It guarantees nothing across partitions,
> and nothing across topics.

So the key is not metadata. It is the choice of *what you want ordered relative to what* — and
whatever you key by is the exact scope of your ordering guarantee.

> **Primer — [Kafka: topics, partitions, keys, and offsets](../technology/kafka/topics-partitions-keys.md)**
> The record model, partitions as the unit of both ordering and parallelism, keys and hashing, offsets
> and commits, consumer groups and rebalancing, replication, producer `acks`, and KRaft.

### The decision: key everything by `orderId`

Every record on every topic in this system is keyed by `orderId`. The catalog states the property
that buys, and the property it does not:

> This gives per-order ordering within a partition, which is the property the workflow actually
> depends on (an order's `InventoryReserved` must not be processed after its `PaymentAuthorized`).
> It provides no cross-order ordering, and the implementation must not assume any.

That second sentence is the more useful half. Order 100's events are ordered with respect to each
other. Order 100's events have no defined ordering with respect to order 101's, ever. Any logic that
assumes otherwise is a bug that will appear under load and vanish when you try to reproduce it.

> **Not yet — and it's bigger than it looks.** Keying by `orderId` orders one order's events within
> *one topic's* partition. Order Service consumes **three different topics**, each with its own
> partitions and its own offsets, and Kafka guarantees nothing between them. Phase 0 did not notice
> this. It took until after Phase 10 to find, and the fix is ADR-009 — built in
> [Chapter 4](../04-reliability/README.md), with the story of how it was found in
> [Chapter 10](../10-retrospective/README.md).

### The rule: a service publishes only to its own topic

| Topic | Published by | Carries |
|---|---|---|
| `orders.events` | Order Service | `OrderCreated`, `PaymentRequested` |
| `inventory.events` | Inventory Service | `InventoryReserved`, `InventoryReservationFailed`, `InventoryReleased` |
| `payments.events` | Payment Service | `PaymentAuthorized`, `PaymentRejected` |
| `fulfillment.events` | Fulfillment Service | `ShipmentCreated` |
| `orders.dlq`, `inventory.dlq`, `payments.dlq`, `fulfillment.dlq` | the failing **consumer** | Records that exhausted retries, plus failure metadata |

Topics are **domain-oriented**, one per publishing service — not one per event type, and not one per
consumer.

The immediately surprising row is `PaymentRequested` on `orders.events`. It is a payment-flavored
event sitting on the orders topic. The reason is precise:

> the alternative would make Payment Service both a producer and a consumer of the same topic, so
> every Payment Service consumer would have to filter out its own output.

That is worth sitting with, because it generalizes. Once "a service publishes only to its own topic"
is the rule, the awkward case resolves itself automatically and every consumer knows, from the topic
name alone, which service's output it is reading. A topic-per-event-type scheme would have avoided the
surprise here and created a proliferation of near-empty topics and a routing table nobody can hold in
their head.

### DLQ topics are routing targets, not event types

There is one DLQ per **consuming** domain, and the rule for which one a record goes to is the part
people get backwards. From `KafkaTopics.java`:

```java
/**
 * Dead-letter topics, one per consuming service — routing targets, not domain event types
 * ...
 * A service dead-letters to its <em>own</em> domain's DLQ
 * regardless of which topic the failing record came from: Inventory Service consumes
 * {@code orders.events} and {@code payments.events}, and both dead-letter to
 * {@code inventory.dlq}, because the failure belongs to the consumer, not to the publisher.
 */
```

**The failure belongs to the consumer, not to the publisher.** Order Service published a perfectly
good record; Inventory Service could not process it. Routing that to `orders.dlq` would file
Inventory's bug under Order's name, and whoever owns Inventory Service would never look there.

### Partition count

`KafkaTopicConfig` declares all eight topics explicitly rather than relying on broker auto-creation,
at **3 partitions, replication factor 1**:

```java
private static final int PARTITIONS = 3;
private static final int REPLICATION_FACTOR = 1;
```

Two reasons for declaring rather than auto-creating, and the code comments give both. Determinism —
auto-created topics get broker defaults, which are not yours. And for the DLQs specifically: *"a DLQ
that only exists because a broker auto-created it on first dead-letter would have broker-default
partitioning, and Phase 4's whole point is that the failure path is as real and as deterministic as
the happy path."*

Remember the number 3. It is the ceiling on consumer parallelism per group, and
[Chapter 8](../08-observability-and-scaling/README.md) runs into it directly: a fourth replica in the same
consumer group gets no partitions and does nothing.

Replication factor 1 means a single broker with no redundancy. If it dies, the data is gone. That is a
deliberate scope decision (`docs/planning/project-overview.md` rules out "full production Kafka
operations") and one to state plainly rather than let someone discover.

---

## Delivery semantics, stated honestly

> At-least-once. Consumers must tolerate duplicate delivery [...] This project does **not** implement
> exactly-once semantics, and no document, UI string, or README may claim it.

This is a project rule with its own entry in `docs/planning/engineering-rules.md` (rule 18), and it is
worth understanding why it is enforced so aggressively.

"Exactly-once" is the most oversold phrase in messaging. Kafka does offer exactly-once *semantics*
within a bounded scope — transactional reads and writes where both sides are Kafka. The moment your
consumer's side effect is a database write, an HTTP call, or an email, that guarantee does not extend
to it: Kafka cannot make your side effect part of its transaction. What you get instead is
at-least-once delivery plus an idempotent consumer, which produces the same *observable* outcome by a
completely different mechanism.

Claiming exactly-once when you mean "at-least-once plus deduplication" is the kind of thing an
interviewer notices immediately, and it is why the project bans the phrase outright.

The catalog also records the gap that existed when it was written:

> Until Phase 6 (transactional outbox), publishers persist their business change and then publish —
> so a crash between commit and publish loses the event.

A known limitation, written down at the time, rather than discovered later. [Chapter 6](../06-outbox/README.md).

---

## Events deliberately excluded

Phase 0 also recorded what it decided *not* to have — three candidates, each with a reason:

| Candidate | Why excluded |
|---|---|
| `OrderShipped` | In v1 a shipment is created and the order is immediately `FULFILLED`. There is no separate dispatch step, so the event would carry no information `ShipmentCreated` doesn't. |
| `FulfillmentRequested` | It would make `PAID` → `FULFILLMENT_PENDING` event-driven and mirror `PaymentRequested` nicely — but Fulfillment Service consumes `PaymentAuthorized` directly, so nobody needs to send it a request. Rejected as a cosmetic gain that contradicts a frozen design doc. |
| Notification events | The Notification Service they belong to is explicitly "only after the core system is stable." |

A list of things you decided not to build, with reasons, is worth as much as the list of things you
did. It is the difference between "we didn't think of that" and "we considered it and here's the
tradeoff" — and the second is the only one of the two that is an answer.

---

## What this looks like in code

`services/common/src/main/java/com/orderfulfillment/common/events/EventEnvelope.java` — the whole
thing:

```java
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID correlationId,
        String aggregateId,
        T payload
) {
}
```

A Java **record**: an immutable data carrier where the compiler generates the constructor, accessors,
`equals`, `hashCode`, and `toString` from the header. Exactly right for a message envelope, which
should never be mutated after construction.

The generic `T payload` is the interesting part, and its Javadoc explains the asymmetry:

> producers build an envelope over a concrete payload record; consumers first deserialize with
> `payload` typed as `JsonNode` [...] and then convert it to the concrete payload type once
> `eventType` is known, since a single topic can carry more than one event type.

That is the envelope pattern's whole point expressed in one type parameter. A producer knows what it
is sending, so it uses `EventEnvelope<OrderCreatedPayload>`. A consumer does not know yet, so it
deserializes to `EventEnvelope<JsonNode>` — a parsed-but-uninterpreted JSON tree — reads `eventType`,
and only then converts the payload to the right type. [Chapter 3](../03-kafka-and-services/README.md) walks
through the `EventCodec` that does this.

Alongside it, `EventTypes.java` pins the eight strings and the current version in one place:

```java
public static final String ORDER_CREATED = "OrderCreated";
// ...
/** Every eventType in this catalog is at eventVersion 1 (event-catalog.md §5). */
public static final int CURRENT_VERSION = 1;
```

---

## Where the contract and the code disagree

The event catalog's topic table (§2) lists a sixth topic:

> | `demo.events` | Scenario Service | Scenario-run lifecycle records used to build the run timeline. Not domain events; no entry in §3. |

**This topic does not exist.** It appears nowhere in `KafkaTopics`, nowhere in `KafkaTopicConfig`,
and nowhere else in the codebase. Scenario Service builds its run timeline by writing
`scenario_run_timeline` rows through `TimelineRecorder` and pushing them straight out over SSE —
persist first, then publish to subscribers, *"so a subscriber never sees an entry that isn't durable
yet."* No Kafka is involved. Scenario Service does use a `KafkaTemplate`, but only to publish
duplicate and poison records onto the **existing domain topics** for Scenarios 4 and 6.

So `demo.events` is a Phase 0 idea that the implementation solved a simpler way and the contract was
never updated to match. It is harmless — nothing depends on it — but it is a live example of exactly
what this chapter's [introduction](README.md) warns about: frozen is a discipline, not a guarantee,
and load-bearing claims get checked against the code.

---

[← Boundaries and ownership](1-boundaries-and-ownership.md) · [Next: State and API contracts →](3-state-and-api-contracts.md)
