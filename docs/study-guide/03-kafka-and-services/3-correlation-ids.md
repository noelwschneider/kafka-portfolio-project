# 3.3 — Correlation IDs

[← Producing and consuming](2-producing-and-consuming.md) · [Next: The split →](4-the-split.md)

A short section about one field, because it is the difference between a debuggable distributed system
and an undebuggable one.

---

## Why now

In [Chapter 2](../02-domain/README.md) an order was one HTTP request, one thread, one stack trace. If
something went wrong you had all of it in front of you.

That is now gone. A single order produces log lines in four processes, on four different threads, none
of which is the thread that handled the HTTP request. The stack trace of a failure in Fulfillment
Service tells you nothing about the order that caused it, and nothing about which of the fifty orders
in flight it belongs to.

ADR-001 listed this as an accepted cost and named the mitigation in the same sentence:

> Debugging spans process boundaries, which is why correlation IDs are a required envelope field
> rather than a nice-to-have.

**Required**, not optional. `correlationId` is one of the six mandatory envelope fields from
[Chapter 1](../01-design-contract/2-the-event-contract.md), and `EventPublisher` throws rather than
publish an event without one.

---

## The mechanism

One UUID, generated once by whoever starts a workflow — Order Service on `POST /api/orders`, or
Scenario Service at the start of a run — and **copied by every consumer onto every event it publishes
in reaction.**

That copying rule is the whole thing. Order Service stamps `OrderCreated`. Inventory Service consumes
it, and when it publishes `InventoryReserved` it carries the same value forward rather than generating
a new one. So does Payment, so does Fulfillment. One order's entire event chain, across four services,
shares one identifier.

Three transports carry it — an HTTP header inbound, an envelope field between services, and two
`ThreadLocal`s inside each process (one for application code, one for the logging framework's MDC).

> **Pattern — [Correlation ID propagation](../patterns/correlation-id-propagation.md)**
> The full mechanism: `CorrelationIdFilter` for HTTP, `runInScope` for Kafka listeners, why
> `EventPublisher` reads from ambient scope rather than taking a parameter, the three ways this goes
> wrong (stale `ThreadLocal`s on pooled threads, async boundaries, setting one scope but not the
> other), and how it relates to real distributed tracing.

---

## The two lines that matter in this chapter

**Every `@KafkaListener` wraps its work:**

```java
public void onMessage(String message) {
    EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
    CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
}
```

An HTTP filter cannot help here — a consumer thread has no request. The listener establishes the scope
itself, from the envelope it just read. Without this line, everything the handler does is
untraceable, and any event it publishes fails outright.

**And `EventPublisher` fails loudly if nobody did:**

```java
UUID correlationId = CorrelationIdHolder.get();
if (correlationId == null) {
    throw new IllegalStateException("No correlationId in scope while publishing " + eventType
            + " — every publish site must run within an HTTP request or a @KafkaListener that set one");
}
```

This is the design decision worth understanding, because it is a trade rather than a free win.

Reading from ambient scope means `EventPublisher.publish` takes no correlation-ID parameter, and
neither does anything between the entry point and the publish site. **That is what makes the pattern
survivable.** Threading the ID explicitly would mean adding a parameter to every service method,
every repository call, every helper — which is where most attempts at this quietly die, because
somebody eventually adds a code path that does not thread it.

The cost is an invisible dependency: this code only works because something upstream set a
`ThreadLocal`. The exception is what makes that cost acceptable. A publish site outside any scope
fails immediately, in development, naming the rule it broke — instead of writing an event with a null
correlation ID that nobody notices until a trace comes up empty three weeks later.

**An implicit dependency with a loud failure is a completely different thing from an implicit
dependency with a silent one.** That generalizes well beyond this project.

---

## What it gets you, concretely

```
docker compose logs | grep d89512f7-b544-4170-b66b-2e93f475ea8f
```

Every log line, from all five services, belonging to one order — in order. The HTTP request that
created it, Inventory's reservation, Payment's authorization, Fulfillment's shipment, and each of
Order Service's status transitions.

The same value is also in:

- **the `ApiError` response body** ([Chapter 2](../02-domain/3-the-http-layer.md)), so a user
  reporting a failure hands you the exact search term;
- **every event envelope**, so [Chapter 5](../05-scenarios-and-frontend/README.md)'s Event Explorer can
  group a workflow's events;
- **the response's `X-Correlation-Id` header**, echoed back to the caller.

> **Not yet.** At this point the grep works because the ID is in the log *message*. It is not yet a
> structured *field*. [Chapter 8](../08-observability-and-scaling/README.md) adds ECS structured logging,
> which puts MDC entries under `labels.correlationId` automatically — turning a text grep into a
> queryable field. The MDC half of `runInScope` is written now precisely so that upgrade is a
> configuration change rather than a code change.

---

## What this is not

It gives you **correlation**, not **tracing**. You can find every line belonging to one operation. You
cannot get a timing waterfall showing where the time went, because there are no spans, no parent/child
relationships, and no duration data.

Real distributed tracing — OpenTelemetry, Zipkin, Micrometer Tracing — provides all of that, and
propagates automatically through W3C `traceparent` headers. Building this by hand instead is a
deliberate scope decision: one envelope field and two `ThreadLocal`s, versus a collector, a backend,
and an agent. Worth being able to say plainly, along with what you would reach for if the system
needed more.

---

[← Producing and consuming](2-producing-and-consuming.md) · [Next: The split →](4-the-split.md)
