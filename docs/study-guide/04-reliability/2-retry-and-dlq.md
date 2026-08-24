# 4.2 — Retry and the dead-letter queue

[← Idempotent consumers](1-idempotent-consumers.md) · [Next: Inventory contention →](3-inventory-contention.md)

Idempotency handles a record arriving twice. This section handles a record that **cannot be
processed at all**.

---

## The problem: a failure blocks everything behind it

A consumer reads a record and throws. Its offset is not committed, so the record is redelivered. It
throws again. And again.

That is correct behavior for a *transient* failure — a lock conflict, a connection blip — and
catastrophic for a permanent one. Because Kafka delivers records from a partition **in order**, a
record that can never succeed blocks every record behind it in that partition, forever. One malformed
payload takes out every order whose key hashes to the same partition.

So two decisions are needed:

1. **Is this failure worth retrying?**
2. **What happens when it is not, or when retries run out?**

Neither has a universally right answer, which is why this is a designed policy rather than a default.

---

## Retryable vs. non-retryable

The distinction is not "how bad is it" but **"could the identical input succeed on a second attempt?"**

Some failures provably cannot:

```java
private static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(
        UnsupportedEventVersionException.class,
        JacksonException.class,
        NonTransientDataAccessException.class,
        IllegalArgumentException.class);
```

Each earns its place:

- **`UnsupportedEventVersionException`** — required to be non-retryable by the event catalog's
  versioning rule: *"Retrying cannot fix a schema it doesn't understand."*
- **`JacksonException`** — the bytes are not the envelope we expect. *"The same bytes will not parse
  differently in 500ms."*
- **`NonTransientDataAccessException`** — Spring's own name for *"this will fail the same way if you
  try again"*: constraint violations, bad SQL, impossible domain data.
- **`IllegalArgumentException`** — a malformed value that reached the domain layer.

The third is the elegant one. Spring's data-access exception hierarchy already splits into
`TransientDataAccessException` and `NonTransientDataAccessException`, so classifying on the base type
inherits a distinction Spring maintains for every database driver.

And the sibling that is **deliberately absent**:

> Its sibling `TransientDataAccessException` (which `ObjectOptimisticLockingFailureException` extends)
> is deliberately *not* here.

An optimistic-lock conflict is the archetypal retryable failure — it means someone else committed, so
a fresh read will see different state. [Section 3](3-inventory-contention.md) depends on that.

### The default direction, and why

> Everything else defaults to retryable. That is the safer default for an *unrecognised* failure: a
> wrongly-retried permanent failure costs 3.5 seconds and still ends in the DLQ with its metadata
> intact, whereas a wrongly-non-retried transient failure discards real work.

**Asymmetric costs, so the default follows the cheaper mistake.** That reasoning transfers to almost
every classifier you will ever write, and stating it is what turns a default from an accident into a
decision.

---

## The retry budget

```java
private static final int MAX_RETRIES = 3;
private static final long INITIAL_INTERVAL_MS = 500L;
private static final double MULTIPLIER = 2.0;
private static final long MAX_INTERVAL_MS = 2_000L;
```

Three retries after the initial delivery — four deliveries, spaced 0.5s / 1s / 2s, about 3.5 seconds
total.

These numbers are argued rather than picked, and the argument is the useful part:

> - Retrying blocks the partition — every later record for those orders waits. A budget an order of
>   magnitude larger would turn one poison record into a visible outage of the whole partition, which
>   is a worse failure than dead-lettering promptly.
> - The retryable class here is genuinely transient (lock contention, a connection blip); such
>   failures clear in milliseconds-to-seconds, so extra attempts buy nothing.
> - Scenario 6 asks a reviewer to *watch* retries happen and then see the record land in the DLQ.
>   3.5 seconds is long enough to see in a UI and short enough to sit through.

Two general points and one specific to this project. The first is the one people miss: **a retry
budget is a decision about how long you are willing to block a partition**, not just about how many
chances to give a record.

**Exponential, not fixed**, and the reason is sharper than "exponential is standard":

> the point of backoff is to sample a different moment, not to wait a fixed amount.

A retry 500ms later and a retry 3.5s later are asking about *different* system states. Fixed
intervals ask the same question repeatedly.

**Jitter is switched off, deliberately:**

```java
backOff.setJitter(0L); // deterministic spacing: the retry timing is part of what Scenario 6 shows
```

Note that this runs *against* general practice — jitter normally prevents synchronized retry storms.
Here the retries are being demonstrated, and a reviewer watching a timeline should see 0.5, 1, 2, not
0.4, 1.3, 1.8. A deliberate deviation with a stated reason, which is the only acceptable kind.

---

## Wiring it up

The whole of a service's share:

```java
@Configuration
public class InventoryKafkaReliabilityConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerErrorHandlerFactory factory) {
        return factory.create(KafkaTopics.INVENTORY_DLQ);
    }
}
```

Nine lines per service. The policy lives once in `common`; the only per-service input is the
destination topic.

> Shared rather than copy-pasted because the parts that matter — which exceptions are worth retrying,
> how many times, and what metadata the dead-lettered record carries — must be the same in all four
> services for the DLQ inspector and the reliability claims to mean one thing.

That is the test for whether something belongs in a shared module: **would divergence make a
system-level claim false?** If yes, share it. If no, duplication is usually cheaper than coupling.

One Spring Boot detail makes it this small:

> Spring Boot's Kafka auto-configuration applies a single `CommonErrorHandler` bean to the listener
> container factory, so this one bean covers both of this service's listeners without either of them
> having to name it.

And the routing rule from [Chapter 1](../01-design-contract/2-the-event-contract.md), restated where
it applies:

> Both dead-letter to `inventory.dlq` even though they consume `orders.events` and `payments.events`
> respectively: the DLQ belongs to the failing consumer, not to the publisher of the record it choked
> on.

---

## The recoverer

```java
DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
        kafkaTemplate,
        // Partition -1 leaves the partition unset on the outbound record, so the producer
        // partitions it by key (= orderId), keeping per-order ordering inside the DLQ too.
        (record, exception) -> new TopicPartition(dlqTopic, -1));
```

`DeadLetterPublishingRecoverer` runs when the retry budget is exhausted (or the failure is classified
non-retryable), publishing the record to the destination the resolver picks.

**Partition `-1`** is a small decision with two justifications. Spring's default resolver sends to the
*same partition number* the record came from, which assumes the DLQ has at least as many partitions
as every source topic. Leaving it unset lets the producer partition by key, which keeps per-order
ordering inside the DLQ too — so a DLQ inspector reading one partition sees an order's failures in
order.

### The failure metadata

Spring already writes `kafka_dlt-*` headers: original topic, partition, offset, timestamp, consumer
group, exception class, message, stack trace. This project adds five more:

```java
public static final String DELIVERY_ATTEMPTS  = "x-delivery-attempts";
public static final String FAILURE_CLASS      = "x-failure-class";
public static final String FAILURE_MESSAGE    = "x-failure-message";
public static final String RETRYABLE          = "x-failure-retryable";
public static final String DEAD_LETTERED_AT   = "x-dead-lettered-at";
```

Each exists for a reason:

**`x-delivery-attempts`** — Spring does not provide it, and Scenario 6 requires *"the error
inspectable and the retry count shown"*. Supplied by a `DeliveryAttemptTracker` registered as a retry
listener on the error handler.

**`x-failure-class` / `x-failure-message`** — the **root cause**, as opposed to Spring's own
`kafka_dlt-exception-fqcn`, which reports the wrapper:

> The wrapper is the same for every failure and so says nothing; the root cause is the answer to "why
> did this record fail", and it is what the classifier actually acted on.

**`x-failure-retryable`** — which arm the classifier took:

> so the DLQ record can state which arm it took rather than leaving a reader to infer it from a count.

That is a genuinely good instinct. A reader could *deduce* it from `x-delivery-attempts` being 1 versus
4 — but deduction requires knowing the policy, and the policy can change. Record the decision, not
just its evidence.

**`x-dead-lettered-at`** — when it was dead-lettered, as distinct from when it was produced.

And the record goes to the log as well as the header:

```java
log.error("Dead-lettering {}-{}@{} to {} after {} delivery attempt(s) ({} failure)",
        record.topic(), record.partition(), record.offset(), dlqTopic, attempts,
        retryable ? "retryable" : "non-retryable", exception);
```

> an operator looking at the service's own logs should not have to go and read the dead-letter topic
> to find out why.

Note the exception is passed as the trailing argument — SLF4J attaches the full stack trace rather
than calling `toString()` on it.

### Classification, exposed twice

```java
public static boolean isRetryable(Throwable throwable) {
    for (Throwable t = throwable; t != null; t = t.getCause() == t ? null : t.getCause()) {
        for (Class<? extends Exception> nonRetryable : NON_RETRYABLE) {
            if (nonRetryable.isInstance(t)) return false;
        }
    }
    return true;
}
```

**Causes are walked**, because a listener exception arrives wrapped in
`ListenerExecutionFailedException`. Checking only the top-level type would classify every failure as
retryable.

Note the `t.getCause() == t ? null : t.getCause()` guard — a self-referential cause would otherwise
loop forever. Rare, and cheap to defend against.

---

## What the DLQ is and is not

**It is** a place a record goes so it stops blocking the partition, carrying enough metadata to
diagnose it later.

**It is not** an automatic recovery mechanism. Nothing reprocesses a DLQ record. Getting a
dead-lettered order moving again is a human decision, and the reliability doc is explicit that this
is a known boundary.

**One thing it *is* wired to**, though — and this is Sprint 2's addition:

```java
static final String DEAD_LETTER_LISTENER_ID = "orders-dlq";
static final String DEAD_LETTER_GROUP_ID = "order-service-dlq";
```

`OrderDeadLetterConsumer` listens on Order Service's own `orders.dlq` and marks the order `FAILED` —
transition 9 of the state machine. That closes the loop the original design left open: an order whose
event could not be processed no longer sits at whatever status it happened to reach, pretending to be
in progress.

Two details in that listener are worth stealing:

**A distinct consumer group** from the domain listeners, so it does not compete for partitions or
offsets with them.

**No ledger claim**, and the reason is sharp:

> a dead-letter record has no reliable `eventId` to key one on — the poison-bytes case that is the
> most common reason a record reaches the DLQ is exactly the case where the envelope may not parse at
> all.

Idempotency instead comes from the state machine's own terminal-state guard: once an order is
`FAILED`, a redelivered dead-letter record for it classifies as stale and writes nothing. **Reusing
an existing invariant instead of adding a second mechanism** — see
[section 4](4-out-of-order-transitions.md) for how that guard works.

> **We got this wrong.** Transition 9 was in the frozen state machine from Phase 0 and went
> unimplemented until Sprint 2. ADR-009's accepted costs named it as a known gap rather than letting
> it pass silently. [Chapter 10](../10-retrospective/README.md).

---

## Demonstrating it

**Scenario 6 (Poison Message / DLQ)** publishes a record that cannot be processed and asserts it
lands in the expected DLQ. The test must publish a **genuinely** malformed record — which is why the
test base injects a raw `KafkaTemplate` alongside `EventPublisher`:

> For publishing records `EventPublisher` deliberately cannot produce — an envelope with an
> `eventVersion` the codec rejects, or a payload that will not deserialize. Phase 4's poison-message
> scenario needs a genuinely malformed record on the wire, not a mocked failure.

Assert on all three: the record is on the DLQ topic, `x-failure-retryable` says `false`,
`x-delivery-attempts` says `1`. That last one proves the *non-retryable path* was taken rather than
the record merely having failed four times — the same "assert the dangerous path was taken" principle
from [Chapter 2](../02-domain/5-testing.md).

---

[← Idempotent consumers](1-idempotent-consumers.md) · [Next: Inventory contention →](3-inventory-contention.md)
