# 1.1 — Boundaries and ownership

[← Chapter 1](README.md) · [Next: The event contract →](2-the-event-contract.md)

Two questions, answered before any code exists: **where are the lines between services**, and
**what crosses them**.

---

## The problem: a boundary is a promise about change

The usual framing of "how do I split this into services" is about size, and size is the least useful
criterion available. A service is not a unit of code volume. It is a unit of **independent change and
independent failure**.

Draw a line between two pieces of a system and you are asserting three things:

1. They can be deployed separately. Changing one does not require rebuilding the other.
2. They can fail separately. One being down degrades the system rather than stopping it.
3. Neither depends on the other's internals — only on an agreed interface between them.

If any of those three is false, the line is decorative. Two "services" that share a database table
are one service with extra network hops and worse failure modes. This is the well-known distributed
monolith: all the operational cost of microservices, none of the independence.

So the real question is not "how many services" but "along which seams does this system actually
change and fail independently?"

### The seams in an order workflow

An order passes through inventory reservation, payment authorization, and shipment creation. Ask the
three questions of each step:

- **Does it change independently?** Payment logic changes for reasons that have nothing to do with
  stock counting. Yes.
- **Does it fail independently?** A payment provider outage has nothing to do with the inventory
  database. Yes.
- **Does each need the others' internals?** Inventory needs to know an order was placed and what was
  on it. It does not need to know what the order cost, who the customer is, or whether payment
  succeeded — except insofar as a decline means "release what you reserved." No.

Those are real seams. This project draws four services along them, plus a fifth that is not a
business service at all.

---

## The five services

| Service | Owns | Exists because |
|---|---|---|
| **Order Service** | The order, its items, its status, its history | Something must own the order's lifecycle and be the single writer of its status |
| **Inventory Service** | Stock levels and reservations | Stock is a contended, concurrently-mutated resource with its own consistency rules |
| **Payment Service** | Payment attempts | The step most obviously owned by an external system in a real build |
| **Fulfillment Service** | Shipments | The terminal step; the thing that turns an authorized payment into a physical promise |
| **Scenario Service** | Scenario runs, timelines, an event projection | Not a business service — the demo control plane (ADR-002, [section 3](3-state-and-api-contracts.md)) |

Four, not twelve. `docs/planning/project-overview.md`'s scope principles say it directly: *"Favor a
few well-designed services over many superficial microservices"* and *"Do not create ten or more
microservices merely to appear sophisticated."* There is no Product Service, no Customer Service, no
Notification Service — and the absence of a Product Service has a visible cost that the project
records rather than hides (product `display_name` lives in Inventory Service, `unit_price` lives in
Order Service, and `docs/db-ownership.md` explains why under "Where prices come from").

That cost being *documented* rather than *fixed* is itself the lesson. Every boundary has a price.
The discipline is knowing what you paid.

---

## What crosses the boundary: events, not calls

Having drawn the lines, the next decision is how the pieces talk. This is ADR-001, and it is the
single most consequential decision in the project.

### The technology, first

Two shapes are available, and the choice between them is the single most consequential one in the
project.

**Synchronous request/response** is simpler to reason about — the whole workflow is one function,
readable top to bottom — but availability multiplies. Three services at 99% uptime, called in
sequence within one request, give roughly 97%, and a restart downstream *fails* the order rather than
delaying it.

**Asynchronous messaging** buys independence and pays for it in immediacy and observability. Nothing
is knowable at the moment of the request, and the workflow exists nowhere as readable code.

Within asynchronous messaging there is a second split that decides Kafka vs. RabbitMQ: a **queue**
hands a message over and forgets it, while a **log** appends records to an ordered, retained sequence
that consumers read at their own pace, each holding an offset. Retention is what makes "pause a
consumer, watch the backlog build, resume it, watch it catch up" demonstrable at all.

> **Primer — [Messaging: queues vs. logs](../technology/kafka/log-vs-queue.md)**
> Synchronous vs. asynchronous in full, what a queue is good at, what a log buys you, and honest
> guidance on which to actually choose.

### The decision, and the honest version of it

ADR-001 chooses Kafka, and it is unusually candid about why:

> A four-step workflow over four demo SKUs does not need a distributed log on its own merits. What
> needs it is the set of behaviors the project exists to demonstrate.

That sentence is worth internalizing, because it is the correct answer to the obvious interview
question ("isn't this over-engineered for four SKUs?"). Yes. Deliberately. The *product* is the
demonstration of partition-level ordering, consumer groups, offsets, replay, duplicate delivery, and
dead-lettering. The order domain is a vehicle.

The ADR names what it rejected and, importantly, does not pretend the rejected options were bad:

- **Synchronous REST orchestration** — *"genuinely the right answer for a system of this size."*
  Rejected because it demonstrates none of the target behaviors and couples the order's availability
  to every downstream service.
- **RabbitMQ** — adequate, lighter to operate. Rejected because messages are consumed off a queue
  rather than retained in a log, so the outage-and-replay scenario is a much weaker demonstration and
  offsets do not exist in the same form. Explicitly *not* rejected on throughput; throughput is
  irrelevant at this scale.
- **A database-backed job queue** — no new infrastructure, and transactional with the business write,
  which would have removed the dual-write problem [Chapter 6](../06-outbox/README.md) exists to solve.
  Rejected because it teaches nothing about messaging.
- **Event sourcing / CQRS** — an explicit non-goal. Events here are notifications between services,
  **not** the system of record. Each service keeps its own state in its own tables. This distinction
  trips people up constantly: an event-driven system is not an event-sourced one.

### The rule that falls out of it

> **No synchronous service-to-service call anywhere in the order workflow.**

One exception, and it is not workflow: Scenario Service calls the other services over HTTP to arm the
payment simulator and pause consumers. That is a control plane, and [section 3](3-state-and-api-contracts.md)
covers why the exception is defensible.

If you take one structural idea from this project, take this one. Every arrow in the architecture
diagram between two business services is a Kafka topic, and there is no arrow that isn't.

---

## Ownership of data (ADR-004)

The second half of a boundary is persistence. A service that cannot be reasoned about without knowing
another service's tables does not have a boundary.

### The problem

The four domains' data is obviously related. A reservation, a payment attempt, and a shipment all
reference the same order. The natural relational design is one schema with foreign keys between them,
and it would make the obvious query — *show me this order with its reservation, payment and shipment*
— a single join.

It would also make the boundaries fictional, for a reason worth stating precisely: **two services
writing the same table cannot be deployed, restarted, or migrated independently.** A migration
belongs to whoever runs it last. That is not a style objection; it breaks the Phase 3 exit criteria
outright.

### The decision

Every table has exactly one owning service. Only the owner reads or writes it. Cross-service data
travels as events, never as shared SQL.

- **One schema per service** — `order_service`, `inventory_service`, `payment_service`,
  `fulfillment_service`, `scenario_service` — each with its own Flyway migration history.
- **One PostgreSQL server locally.** The boundary is enforced by convention and construction, not by
  network separation. Four database containers to enforce a rule a code review already enforces was
  judged disproportionate — and the schema-per-service layout means splitting later is a
  configuration change, not a migration.
- **No foreign keys across schemas.** `order_id` appears in four schemas and is a foreign key in
  exactly one: `order_service`, where `orders` actually lives. Everywhere else it is a correlation
  identifier the database cannot enforce.
- **Reliability tables are per-service, not shared.** Each service gets its own `processed_events`
  table with identical DDL.

That last point has a correctness argument behind it that is much better than "it seemed tidier," and
it is worth having ready: the deduplication insert **must commit in the same local transaction as the
business change it guards**. If the ledger lived in another service's schema, that transaction would
span two services and could not exist. [Chapter 4](../04-reliability/README.md) builds the mechanism this
protects.

### Rejected, and why

- **One shared schema with cross-domain foreign keys.** Strongest integrity, simplest queries.
  Rejected because it makes Phase 3's extraction a data migration, and because the integrity is less
  valuable than it looks: an order and its shipment are **eventually consistent by design**, so a
  foreign key would be asserting an invariant the architecture does not hold.
- **A separate PostgreSQL server per service from the start.** The strongest boundary and closest to
  a real deployment. Rejected locally as disproportionate.
- **Shared read access, private write access.** Each service writes only its own tables but may read
  others' for convenience. Tempting — and rejected on the sharpest reasoning in the ADR:
  *read coupling is still coupling.* It makes another service's schema part of your contract, so any
  migration becomes a cross-service coordination problem. It also removes the reason the event
  exists.

### The costs, recorded

- No cross-domain joins. Assembling a full picture of an order means several API calls.
- No referential integrity across boundaries. Every service must tolerate an `order_id` it has never
  seen — not hypothetical under at-least-once delivery and partial failure.
- Duplicated shape: four `processed_events` tables with identical DDL. Deliberate duplication,
  cheaper than the coupling it avoids.
- Five migration histories to keep in order.

---

## What this looks like in code, eventually

Phase 0 produces documents, but two of them get a direct code representation as soon as there is
code, both in `services/common` — the library module every service depends on.

`services/common/src/main/java/com/orderfulfillment/common/kafka/KafkaTopics.java` is the topic table
turned into constants, and its own comment carries the rule rather than leaving it in prose:

```java
/** docs/events/event-catalog.md §2 — frozen topic ownership table. A service publishes only to its
 * own domain topic; this class is the single place topic name strings live. */
public final class KafkaTopics {

    public static final String ORDERS_EVENTS = "orders.events";
    public static final String INVENTORY_EVENTS = "inventory.events";
    public static final String PAYMENTS_EVENTS = "payments.events";
    public static final String FULFILLMENT_EVENTS = "fulfillment.events";
```

Two things to notice, because they recur all over this codebase.

**The Javadoc cites the contract by filename and section.** That is not decoration — it is what makes
the contract enforceable six months later. Someone changing this file is told, in the file, where the
authority for it lives.

**Topic names exist in exactly one place.** A string literal `"orders.events"` scattered across five
services is a typo waiting to become a silently-unconsumed topic, because Kafka will happily create
`orders.event` for you and never tell anyone.

---

## Open question

`docs/adr/ADR-004`'s decision section says *"`outbox_events` exists only in Order Service
(ADR-006)."* That was true when written and is **no longer true** — Sprint 2 added an `outbox_events`
table and dispatcher to Inventory, Payment, and Fulfillment Service as well
([Chapter 6](../06-outbox/README.md)). ADR-006 carries a Sprint 2 correction note; ADR-004 does not. Worth
knowing before you quote ADR-004 at anyone.

---

[← Chapter 1](README.md) · [Next: The event contract →](2-the-event-contract.md)
