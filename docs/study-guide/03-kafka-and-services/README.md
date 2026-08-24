# Chapter 3 — Kafka, and the split into services

**Build history:** Phases 2 and 3, in one commit — `1f2bc50 introduce kafka and split up monorepo`.

Two planned phases that turned out to be one piece of work. Phase 2 was to introduce Kafka in-process;
Phase 3 was to extract the services. In practice, once the domains communicated only through events,
they were already separate in every way that mattered and putting them in separate JVMs was
bookkeeping.

That is not a shortcut — it is the plan working. The reason to sequence it as two phases is that
*if* the extraction had been hard, you would want to know that before doing it.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Events on the wire](1-events-on-the-wire.md) | Serializer choice and why not `JsonSerializer`, the codec and two-stage deserialization, the publisher, declaring topics, and the dual-write gap this leaves open |
| 2 | [Producing and consuming](2-producing-and-consuming.md) | `@KafkaListener` shape, group IDs and listener IDs, the workflow redistributed across four services, and the four things that just got harder |
| 3 | [Correlation IDs](3-correlation-ids.md) | Why a distributed system needs one, `runInScope`, reading from ambient scope with a loud failure, correlation vs. tracing |
| 4 | [The split](4-the-split.md) | Four modules, four schemas, what gets simpler, testing across a boundary, and the exit criterion worth performing by hand |

---

## What changes, in one sentence

`POST /api/orders` stops returning the answer.

Everything else follows from that. The HTTP response becomes an acknowledgement rather than a result;
the workflow stops existing as readable code; transactions stop spanning the workflow; ordering stops
being free; and duplicate delivery stops being an edge case.

## What does not change

Every domain class from [Chapter 2](../02-domain/README.md). `InventoryService.reserve` is called by a
Kafka listener instead of an orchestrator and is otherwise identical. That is the return on keeping
business logic out of controllers and out of the orchestrator — the domain never knew who was calling
it, so changing the caller costs nothing.

> **Not yet — three gaps this chapter deliberately leaves open.** Each is the subject of a later
> chapter, and each is worth *seeing* before it is fixed:
>
> - **Duplicate delivery double-reserves stock.** At-least-once is the guarantee; nothing deduplicates
>   yet. [Chapter 4](../04-reliability/README.md).
> - **A crash between commit and publish loses the event.** Documented in the event catalog as a known
>   limitation rather than hidden. [Chapter 6](../06-outbox/README.md).
> - **Events from different topics can be processed out of order.** `ShipmentCreated` can beat the
>   `PaymentAuthorized` that caused it. [Chapter 4](../04-reliability/README.md), and
>   [Chapter 10](../10-retrospective/README.md) for how it was found.

---

## Build it yourself

**Kafka running** — `apache/kafka` in KRaft mode, port 9092. One container, no ZooKeeper.

**Wire it up** — [section 1](1-events-on-the-wire.md)

1. Add `spring-boot-starter-kafka` to each module.
2. `application.yml`: `bootstrap-servers` as `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`, **String**
   serializers and deserializers both ways, `auto-offset-reset: earliest`, and
   `jackson.deserialization.fail-on-unknown-properties: false`.
3. In `common`: `EventEnvelope<T>` as a record, `EventTypes` and `KafkaTopics` constants, the eight
   payload records from the event catalog.
4. `EventCodec` — `decode` to `EventEnvelope<JsonNode>` with the version check inside it;
   `payloadAs` for the second stage. Plus `UnsupportedEventVersionException`.
5. `EventPublisher` — one `buildEnvelope` method, keyed sends, the explicit-`eventId` overload, and
   the `IllegalStateException` when no correlation ID is in scope.
6. `KafkaTopicConfig` — declare all eight topics explicitly at 3 partitions, replication factor 1.

**Correlation IDs** — [section 3](3-correlation-ids.md)

7. `CorrelationIdHolder` (two `ThreadLocal`s — the holder and the SLF4J MDC) with `set`, `get`,
   `clear`, and `runInScope`.
8. `CorrelationIdFilter` — accept `X-Correlation-Id` or generate, replace a malformed one, echo it
   back, **clear in a `finally`**.

**Consumers** — [section 2](2-producing-and-consuming.md)

9. A `*Consumers` constants class per service holding listener IDs *and* consumer names, both as
   compile-time constants.
10. Order Service publishes `OrderCreated` from `createOrder` and stops orchestrating.
11. Six listeners, all four steps each — decode, `runInScope`, filter by `eventType`, delegate:
    - Inventory ← `orders.events` (`OrderCreated`)
    - Payment ← `orders.events` (`PaymentRequested`)
    - Order ← `inventory.events` (`InventoryReserved`, `InventoryReservationFailed`)
    - Order ← `payments.events` (`PaymentAuthorized`, `PaymentRejected`)
    - Fulfillment ← `payments.events` (`PaymentAuthorized`)
    - Inventory ← `payments.events` (`PaymentRejected`)
    - Order ← `fulfillment.events` (`ShipmentCreated`)
12. **Delete `SynchronousOrderWorkflow`.**

**Split** — [section 4](4-the-split.md)

13. Four modules under `services/`, each with its own `pom.xml` and boot plugin. Move each domain
    package, its migrations, and its tests.
14. Four application classes at `com.orderfulfillment.<domain>`, plus `scanBasePackages` (or an
    auto-configuration) so `common`'s beans are still found.
15. Four `application.yml` files: distinct `spring.application.name`, `server.port` 8081–8084,
    `spring.flyway.schemas`. Delete the multi-schema Flyway runner.
16. Per-service `AbstractIntegrationTest`: singleton PostgreSQL **and** Kafka containers, an injected
    `EventPublisher` for simulating upstream services, a raw `KafkaTemplate` for malformed records,
    and a `rawConsumer(topic)` helper using a fresh random group per call.
17. Rewrite each service's tests to prove only its own contract — given these events in, these events
    out.
18. Frontend: five base URLs from `import.meta.env`. CORS configured on all four services.

**Done when:** `POST /api/orders` returns `PENDING` immediately; the order reaches `FULFILLED` with
nothing calling anything; out-of-stock and payment-rejection paths still work end to end, the latter
releasing stock through `InventoryReleased`; each service starts and stops independently; and
**stopping Inventory Service, creating an order, and starting it again completes the order** rather
than failing it.

---

## Next

[Section 1 — Events on the wire](1-events-on-the-wire.md).
