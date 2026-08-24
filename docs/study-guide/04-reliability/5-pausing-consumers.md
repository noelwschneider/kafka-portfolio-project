# 4.5 — Pausing consumers

[← Out-of-order transitions](4-out-of-order-transitions.md) · [Chapter 4 ↑](README.md)

A short section about a small class, included because it is the clearest illustration in the project
of what "the scenarios must be real" actually costs — and buys.

---

## What Scenario 5 demands

Scenario 5 (Consumer Outage and Recovery) stops a consumer, lets a backlog build, restarts it, and
shows the backlog drain. `docs/scenarios.md` is unusually specific about the implementation:

> a genuine Spring Kafka listener-container pause, not a discarded message or a simulated delay.

Three ways to make the UI *look* right, two of which are forbidden:

- **Drop the records** while "paused." The UI shows a gap. It is a lie — the records are gone, and
  nothing drains on resume.
- **Buffer them in memory** and replay on resume. Closer, and still a lie: the backlog is in your
  heap, not on the topic, and it dies with the process.
- **Actually pause the consumer.** Records stay on the topic, the offset stays where it is, and the
  consumer resumes from it.

Only the third demonstrates anything about Kafka, and the difference is invisible in a screenshot —
which is exactly why the rule is written down.

---

## `pause()`, not `stop()`

```java
public ConsumerState pause(String consumerName) {
    MessageListenerContainer container = require(consumerName);
    container.pause();
    awaitPausedState(container, true);
    return toState(container);
}
```

`KafkaListenerEndpointRegistry` is Spring Kafka's registry of listener containers, keyed by the `id`
you gave each `@KafkaListener` — the reason [Chapter 3](../03-kafka-and-services/README.md) insisted
those be explicit, stable constants.

Both `stop()` and `pause()` halt processing. The choice between them is the interesting part:

> `stop()` would also halt processing, but it leaves the consumer group, triggering a rebalance on the
> way out and another on the way back — so a multi-instance deployment would reassign the paused
> instance's partitions to a running one and **quietly process the "backlog" anyway**, which is the
> opposite of what the scenario demonstrates.

A demo that works on your laptop and silently stops working at two replicas. It would not error; the
backlog would simply never appear, and you would be left wondering why the scenario looked different
in production.

`pause()` keeps the consumer in the group with its partitions assigned, and just stops delivering
records. It also matches the frozen OpenAPI vocabulary, where the field is `paused`.

---

## Waiting for the pause to take effect

```java
private static final Duration EFFECTIVE_TIMEOUT = Duration.ofSeconds(10);
private static final long POLL_PARK_NANOS = 20_000_000L; // 20 ms

private static void awaitPausedState(MessageListenerContainer container, boolean paused) {
    long deadline = System.nanoTime() + EFFECTIVE_TIMEOUT.toNanos();
    while (container.isContainerPaused() != paused && System.nanoTime() < deadline) {
        LockSupport.parkNanos(POLL_PARK_NANOS);
    }
    if (container.isContainerPaused() != paused) {
        log.warn("Kafka listener '{}' did not reach paused={} within {}; reporting its actual state", …);
    }
}
```

`container.pause()` is a **request**. It takes effect on the container's next poll, so the method
returns before anything has actually paused.

That gap is a real bug in waiting:

> A pause takes effect on the container's next poll, so the call returns only once the pause is real —
> otherwise Scenario 5 could publish its first order into the gap and see it processed by a consumer
> it believes it has already paused.

A scenario that pauses a consumer and immediately creates an order would race the pause, and the
symptom would be *intermittent*: the scenario mostly works, and occasionally the first order sails
through. The worst kind of bug to chase after the fact.

Two properties of the wait are worth copying:

**It is bounded.** Ten seconds, then give up rather than hang.

**On timeout it reports the observed state, not an optimistic one.** The method returns
`toState(container)` — whatever the container actually says — rather than assuming success. A demo
control that lies about having paused something is worse than one that admits it failed.

---

## Idempotent by contract

```java
/** Idempotent: pausing an already-paused listener succeeds, per the frozen OpenAPI description. */
```

Pausing a paused listener succeeds. Resuming a running one succeeds. This is the OpenAPI contract
being honoured rather than a convenience — and it matters because `POST /demo/reset` calls resume
unconditionally to clean up after a run, without needing to know what state anything is in.

---

## Why this is `/demo`, and why that matters later

`ConsumerControl` lives in `common`, and each service exposes it through a `DemoConsumerController`
under `/demo/consumers` — never mixed into `/api`.

ADR-002 explains why the control has to live *inside* the service it affects, even though that puts
demo code in every service:

> a listener cannot be paused from outside its own process anyway. The `/demo` prefix inside each
> service is the smaller compromise: the demo code is local to the service, but visibly quarantined.

**Scenario Service orchestrates but cannot execute.** It makes an HTTP call to
`POST /demo/consumers/{id}/pause` on the target service, which is the one synchronous
service-to-service call the architecture permits — control plane, not workflow.

> **Where this pays off.** [Chapter 9](../09-production/README.md) puts the demo on the public
> internet. `/demo/consumers` is exactly the endpoint you do **not** want a stranger reaching, and the
> ingress allowlist keeps it cluster-internal while the scenario endpoints a visitor needs stay
> routable. That is only possible because the split was made by construction in Phase 0.

Also worth remembering from ADR-002:

> Demo state is real state. A run that fails halfway can leave a paused listener [...] behind, which is
> why `POST /demo/reset` exists and why it reports what it actually reset.

A genuinely paused consumer stays paused. That is the price of it being real, and the reset endpoint
is the thing that pays it.

---

## What it demonstrates

Stop the consumer, create orders, watch them sit at `PENDING`, resume, watch them complete.

The observable that makes it land is **consumer lag** — the number of records between the consumer's
committed offset and the end of the partition. It climbs while paused and drains on resume, and it is
a real Kafka metric rather than an application counter.
[Chapter 5](../05-scenarios-and-frontend/README.md) surfaces it in the System Health page;
[Chapter 8](../08-observability-and-scaling/README.md) uses the same number to show what adding
replicas does.

This is also the same exercise as [Chapter 3](../03-kafka-and-services/README.md)'s exit criterion —
stop Inventory Service, create an order, start it again — turned into something repeatable and
observable rather than something you do by hand with two terminals. That is the whole idea of the
scenario engine, which is [Chapter 5](../05-scenarios-and-frontend/README.md).

---

[← Out-of-order transitions](4-out-of-order-transitions.md) · [Chapter 4 ↑](README.md)
