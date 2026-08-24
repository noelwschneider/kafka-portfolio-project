# Spring for Apache Kafka

*Referenced from [Chapter 3.1 — Events on the wire](../../03-kafka-and-services/1-events-on-the-wire.md).*

Assumes the concepts in [topics, partitions, keys, and offsets](topics-partitions-keys.md).

---

## What the starter gives you

`spring-boot-starter-kafka` on the classpath plus a `bootstrap-servers` value auto-configures a
`ProducerFactory`, a `ConsumerFactory`, a `KafkaTemplate`, a `KafkaAdmin`, and the listener-container
infrastructure behind `@KafkaListener`.

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      group-id: my-service
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
```

## Serialization: why `String` is often the right answer

Spring Kafka ships a `JsonSerializer`/`JsonDeserializer` pair that will map objects to and from JSON
for you. It is convenient and it has two properties worth understanding before you adopt it.

**It writes type headers.** `JsonSerializer` adds a `__TypeId__` header naming the Java class, and
`JsonDeserializer` uses it to pick a target type. That couples your wire format to your package
names: rename or move a class and existing records become undeserializable. Trusted-package
configuration is a further ongoing chore.

**It deserializes before your code runs.** A malformed record fails inside the container, not inside
your listener — which changes how you handle it, and makes "publish a deliberately unprocessable
record" harder to reason about.

The alternative is **`StringSerializer`/`StringDeserializer` plus an explicit codec**: the listener
receives a `String` and calls your own Jackson mapping. More typing, and in exchange the wire format
is exactly what you designed, deserialization failures happen at a point you control, and a malformed
record is an ordinary exception in your own code.

For a system with a designed envelope and a versioning rule, explicit wins. For internal
service-to-service traffic where the JSON shape is nobody's contract, the built-in serializer is
fine.

## `auto-offset-reset`

What a consumer group does when it has **no committed offset** — a brand-new group, or one whose
offsets have expired.

- **`earliest`** — start at the beginning of the topic.
- **`latest`** (the Kafka default) — start at the end, ignoring everything already there.

`latest` is a common source of "my consumer receives nothing": it started after the records were
produced, so from its point of view there is nothing new. `earliest` is usually the right choice for
event consumers, and it means a new consumer group replays the retained history — occasionally what
you want, occasionally a surprise.

This setting applies **only** when there is no committed offset. It has no effect on a group that has
run before.

## Producing

```java
kafkaTemplate.send(topic, key, value);
```

`send` returns a `CompletableFuture`. **Not calling `.get()` on it makes the send fire-and-forget** —
the record is batched and sent asynchronously, and a failure is logged by the Kafka client rather than
thrown to your code.

That is a deliberate choice with a real consequence: if your business transaction already committed
and the send then fails, you have a database change with no event. Blocking on the future does not
fix that either — it narrows the window without closing it. The actual fix is the transactional
outbox pattern.

## Consuming

```java
@KafkaListener(id = "inventory-events", topics = "inventory.events", groupId = "order-service")
public void onMessage(String message) {
    // ...
}
```

- **`topics`** — what to subscribe to.
- **`groupId`** — the consumer group. Same group across instances = shared partitions; different
  groups = independent copies of every record.
- **`id`** — a stable name for the listener *container*, which is what makes it addressable at
  runtime through `KafkaListenerEndpointRegistry`. That is how you pause and resume a listener
  programmatically. Give every listener an explicit `id`; the generated ones are not stable.

### Offset commits

By default Spring Kafka uses `BATCH` ack mode: it commits offsets after the listener returns
successfully for a batch of records. **If your method throws, the offset is not committed** and the
record is redelivered — which is exactly what makes error handling and retry work, and exactly why
duplicate delivery is normal rather than exceptional.

### Concurrency

```yaml
spring:
  kafka:
    listener:
      concurrency: 3
```

Runs N consumer threads for the listener, each assigned a subset of partitions. **Setting this above
the partition count buys nothing** — the extra threads are assigned no partitions and idle.

Concurrency here also means your listener method must be thread-safe, and that records for different
keys are genuinely processed in parallel.

## The listener container

`@KafkaListener` is backed by a `MessageListenerContainer`, which owns the poll loop, offset commits,
error handling, and lifecycle. Things you reach for it for:

- **Pausing and resuming.** `registry.getListenerContainer(id).pause()` stops delivery without
  leaving the consumer group — no rebalance, offsets intact, the backlog just builds.
- **Error handling.** A `DefaultErrorHandler` on the container factory controls retry counts,
  backoff, and where a record goes when retries are exhausted.
- **Knowing whether it is running.** `container.isRunning()`, `container.isContainerPaused()`.

## Testing

`spring-kafka-test` provides an embedded broker, and Testcontainers provides a real one via
`KafkaContainer`. Prefer the real broker: an embedded broker is a different implementation with
different timing, and the behavior you most want to test — rebalances, redelivery, offset
semantics — is exactly where implementations differ.

A useful pattern for assertions is a **raw consumer** in a throwaway group:

```java
props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-observer-" + UUID.randomUUID());
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
```

A unique group per test means it reads the whole topic from the start and never competes with the
application's own consumers for partitions.
