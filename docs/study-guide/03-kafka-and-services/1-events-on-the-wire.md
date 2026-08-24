# 3.1 — Events on the wire

[← Chapter 3](README.md) · [Next: Producing and consuming →](2-producing-and-consuming.md)

Turning the frozen envelope from [Chapter 1](../01-design-contract/2-the-event-contract.md) into
bytes, and back.

---

## What Kafka needs from you

Kafka stores a key and a value, both as bytes. It has no opinion about what is inside them. So before
anything can be published, two decisions have to be made: **what format**, and **who does the
converting**.

> **Primer — [Spring for Apache Kafka](../technology/kafka/spring-kafka.md)**
> What the starter auto-configures, serializer choices and their consequences, `auto-offset-reset`,
> producing and offset commits, `@KafkaListener` and the listener container, concurrency, and testing
> against a real broker.

## The serialization decision

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
```

**Strings both ways** — not Spring Kafka's `JsonSerializer`. The listener receives a `String` and
calls an explicit codec.

That is more typing, and it buys three things this project specifically needs:

- **The wire format is exactly the frozen envelope.** `JsonSerializer` adds a `__TypeId__` header
  naming the Java class, which would put `com.orderfulfillment.common.events.OrderCreatedPayload` on
  the wire — a package name is not part of `docs/events/event-catalog.md`, and renaming a class would
  become a wire-breaking change.
- **Deserialization failures happen in your code**, at a point you control, rather than inside the
  listener container before your method runs. Scenario 6 publishes a deliberately unprocessable
  record, and the failure needs to be an ordinary exception on a path you can reason about.
- **Version checking has somewhere to live.** The catalog's rule — an unknown `eventVersion` is a
  *non-retryable* failure — needs a hook, and an explicit codec is it.

One more line worth pointing at:

```yaml
  jackson:
    deserialization:
      fail-on-unknown-properties: false # envelope versioning rule (event-catalog.md §5): consumers must ignore unknown fields
```

This is the versioning rule made operational. *"Additive, optional payload field → no version bump.
Consumers must ignore unknown fields."* That promise is only keepable if deserialization is configured
to tolerate them — and Jackson's default is to throw. One line of configuration is the difference
between a versioning policy you wrote down and one that works.

---

## The codec

```java
@Component
public class EventCodec {

    private final ObjectMapper objectMapper;

    public EventEnvelope<JsonNode> decode(String json) {
        EventEnvelope<JsonNode> envelope = objectMapper.readValue(json, new TypeReference<>() { });
        if (envelope.eventVersion() != EventTypes.CURRENT_VERSION) {
            throw new UnsupportedEventVersionException(envelope.eventType(), envelope.eventVersion());
        }
        return envelope;
    }

    public <T> T payloadAs(EventEnvelope<JsonNode> envelope, Class<T> payloadType) {
        return objectMapper.treeToValue(envelope.payload(), payloadType);
    }
}
```

Twelve lines that encode the entire envelope idea.

**Two-stage deserialization.** `decode` parses to `EventEnvelope<JsonNode>` — the envelope fully
typed, the payload left as an unconverted JSON tree. That is not laziness; it is *necessary*. A single
topic carries more than one event type (`orders.events` carries both `OrderCreated` and
`PaymentRequested`), so the payload's Java type is unknowable until `eventType` has been read. Only
after the caller's `switch` picks a branch does `payloadAs` convert it.

This is what the generic parameter on `EventEnvelope<T>` is for, and its Javadoc says so:

> producers build an envelope over a concrete payload record; consumers first deserialize with
> `payload` typed as `JsonNode` [...] and then convert it to the concrete payload type once
> `eventType` is known, since a single topic can carry more than one event type.

**The version check is in `decode`,** so it runs before any consumer sees the record — one place, not
five. `UnsupportedEventVersionException` becomes the marker that
[Chapter 4](../04-reliability/README.md)'s error handler classifies as non-retryable: *"Retrying cannot fix a
schema it doesn't understand."*

**No try/catch.** Worth a note if you are used to older Jackson:

> Jackson 3 (Spring Boot 4.1's baseline — `tools.jackson.*`, not the older
> `com.fasterxml.jackson.databind`) makes `ObjectMapper`'s read/write methods throw the unchecked
> `tools.jackson.core.JacksonException` instead of a checked exception.

Note the package: `tools.jackson`, not `com.fasterxml.jackson`. Almost every Jackson example you will
find online uses the old one.

---

## The publisher

```java
@Component
public class EventPublisher {

    public UUID publish(String topic, String eventType, String aggregateId, Object payload) {
        return publish(topic, eventType, aggregateId, UUID.randomUUID(), payload);
    }

    public UUID publish(String topic, String eventType, String aggregateId, UUID eventId, Object payload) {
        String json = objectMapper.writeValueAsString(buildEnvelope(eventType, aggregateId, eventId, payload));
        kafkaTemplate.send(topic, aggregateId, json);
        return eventId;
    }

    public EventEnvelope<Object> buildEnvelope(String eventType, String aggregateId, UUID eventId, Object payload) {
        UUID correlationId = CorrelationIdHolder.get();
        if (correlationId == null) {
            throw new IllegalStateException("No correlationId in scope while publishing " + eventType
                    + " — every publish site must run within an HTTP request or a @KafkaListener that set one");
        }
        return new EventEnvelope<>(
                eventId, eventType, EventTypes.CURRENT_VERSION, Instant.now(), correlationId, aggregateId, payload);
    }
}
```

**One place builds the envelope.** Every outbound record in the system passes through
`buildEnvelope`, which is what makes "every event has these six fields, correctly populated" a
property of the system rather than a convention people follow.

**`kafkaTemplate.send(topic, aggregateId, json)`** — the key is the `aggregateId`, which is the
`orderId`. That single argument is the whole of
[Chapter 1](../01-design-contract/2-the-event-contract.md)'s partitioning decision, and forgetting it
would silently switch the system to round-robin distribution with no per-order ordering at all and no
error anywhere.

**The overload taking an explicit `eventId`** exists for one specific case, and it is a nice piece of
contract detail: `PaymentRequested`'s payload carries an `idempotencyKey` which *is* that event's own
`eventId`. You cannot reference an ID that a method is about to generate, so the caller generates it
first and passes it in.

**`correlationId` is read from ambient scope, not passed.** With a loud failure if it is missing.
That is a pattern in its own right:

> **Pattern — [Correlation ID propagation](../patterns/correlation-id-propagation.md)**
> How one identifier survives an HTTP boundary, a Kafka hop, and a thread change without every method
> signature growing a parameter — and the three ways that goes wrong.

Covered in full in [section 3](3-correlation-ids.md).

### The gap in `publish`

```java
kafkaTemplate.send(topic, aggregateId, json);
```

Not blocked on. `send` returns a `CompletableFuture` that nothing awaits, so this is fire-and-forget:
the record is batched and sent asynchronously, and a failure is logged by the Kafka client rather than
thrown here.

Combined with the fact that the caller's database transaction has usually already committed, that
leaves a real window: **a business change can exist with no event.** The catalog names it:

> Until Phase 6 (transactional outbox), publishers persist their business change and then publish —
> so a crash between commit and publish loses the event.

> **Not yet.** This is a known, documented gap, not an oversight, and it is
> [Chapter 6](../06-outbox/README.md)'s entire subject. Blocking on the future would narrow the window
> without closing it — the fix is structural.

### A stale comment worth knowing about

`EventPublisher`'s Javadoc says:

> Phase 6 closed that gap in Order Service only [...] Inventory, Payment and Fulfillment Service
> still publish this way, deliberately and documented.

**That is no longer true.** Sprint 2 moved all four services onto the outbox — `OutboxRecorder` and
`OutboxPublisher` exist in Inventory, Payment, and Fulfillment Service, and their own consumers'
Javadocs say so explicitly. The comment on `EventPublisher` was not updated. ADR-006 carries the
correction; this class does not.

`EventPublisher.publish` is consequently used far less than its documentation implies — mostly by
Scenario Service, which publishes duplicate and poison records deliberately, and by tests simulating
upstream services.

---

## Declaring the topics

```java
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICATION_FACTOR = 1;

    @Bean
    public NewTopic ordersEventsTopic() {
        return TopicBuilder.name(KafkaTopics.ORDERS_EVENTS).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }
    // …seven more: three domain topics, four DLQs
}
```

Kafka will happily auto-create a topic on first use, with broker-default settings. This declares all
eight explicitly instead, and the comments give both reasons:

> so partition count/replication are deterministic regardless of broker defaults

and, for the DLQs specifically:

> a DLQ that only exists because a broker auto-created it on first dead-letter would have
> broker-default partitioning, and Phase 4's whole point is that the failure path is as real and as
> deterministic as the happy path.

Auto-creation also silently hides typos: publish to `orders.event` and Kafka creates it, no consumer
ever reads it, and nothing anywhere reports an error. Declaring topics and referencing them through
`KafkaTopics` constants removes that failure mode entirely.

**Three partitions** is the number to remember. It is the ceiling on consumer parallelism per group,
and [Chapter 8](../08-observability-and-scaling/README.md) runs straight into it: a fourth replica in one
consumer group is assigned nothing and idles.

**Replication factor 1** means one broker and no redundancy — lose it, lose the data. A deliberate
scope decision (`project-overview.md` rules out "full production Kafka operations"), and one to state
rather than let someone assume otherwise.

`TopicBuilder` beans are applied at startup by Spring Boot's auto-configured `KafkaAdmin`. Note that
Kafka will *increase* a partition count on an existing topic but never decrease it, and increasing it
changes which partition existing keys hash to — so this number is much easier to choose correctly than
to change.

---

[← Chapter 3](README.md) · [Next: Producing and consuming →](2-producing-and-consuming.md)
