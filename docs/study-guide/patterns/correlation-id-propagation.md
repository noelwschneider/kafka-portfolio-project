# Pattern — Correlation ID propagation

**Where it's introduced:** [Chapter 3, section 3](../03-kafka-and-services/3-correlation-ids.md).
**Where it recurs:** every publish site, every `@KafkaListener`, every log line, every error response.

---

## The problem

A single order touches five services, four Kafka topics, five database schemas, and produces a few
dozen log lines. When something goes wrong, the question is always the same: **what happened to this
one order?**

Without a shared identifier, answering it means correlating by timestamp across five log streams and
hoping nothing else happened in the same millisecond. Under any concurrency at all, that does not
work.

The fix is conceptually trivial — put one identifier on everything belonging to one logical operation.
The difficulty is entirely in **propagation**: the identifier has to survive an HTTP boundary, a Kafka
hop, a thread change, and a service boundary, without every method signature growing a parameter.

## The decision

One `correlationId` (a UUID), generated once by whoever starts a workflow, carried through every hop.

**Three transports, one value:**

| Where | How it travels |
|---|---|
| HTTP request → service | `X-Correlation-Id` header (generated if absent) |
| Service → Kafka | `correlationId` field in the [event envelope](../01-design-contract/2-the-event-contract.md) |
| Kafka → service → next event | Read off the consumed envelope, copied onto everything published in reaction |

**Two scopes inside a process:**

| Where | Mechanism |
|---|---|
| Application code (`EventPublisher`, error responses) | A `ThreadLocal` in `CorrelationIdHolder` |
| Log lines | SLF4J's **MDC** (Mapped Diagnostic Context), a logging-framework `ThreadLocal` that the structured-logging encoder writes into every line automatically |

Both are set together, always, and cleared together, always.

## The implementation

### Entry point 1 — HTTP

```java
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        String incoming = request.getHeader(HEADER);
        UUID correlationId;
        try {
            correlationId = incoming != null ? UUID.fromString(incoming) : UUID.randomUUID();
        } catch (IllegalArgumentException ex) {
            correlationId = UUID.randomUUID();
        }
        CorrelationIdHolder.set(correlationId);
        MDC.put(MDC_KEY, correlationId.toString());
        response.setHeader(HEADER, correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationIdHolder.clear();
            MDC.remove(MDC_KEY);
        }
    }
}
```

Four things worth copying exactly:

- **Accept an incoming header, generate one otherwise.** A caller that already has a correlation ID
  keeps it, so the trace spans the caller too.
- **A malformed header is replaced, not rejected.** A caller sending garbage should not get a 400 for
  a diagnostic header — but it must not poison the trace either.
- **Echo it back in the response.** The client now knows the ID for the request it just made, which is
  what makes a bug report actionable.
- **Clear in a `finally`.** Non-negotiable — see below.

### Entry point 2 — a Kafka listener

An HTTP filter cannot help here: a consumer thread has no request. The listener sets the scope itself,
from the envelope it just read:

```java
@KafkaListener(id = "...", topics = KafkaTopics.ORDERS_EVENTS, groupId = "inventory-service")
public void onMessage(String message) {
    EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
    CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
}
```

```java
public static void runInScope(UUID correlationId, Runnable action) {
    set(correlationId);
    MDC.put(CorrelationIdFilter.MDC_KEY, correlationId.toString());
    try {
        action.run();
    } finally {
        clear();
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }
}
```

**Every `@KafkaListener` wraps its work in `runInScope`.** That single line is what carries the trace
across the Kafka hop and into everything the handler does.

### Reading it, rather than passing it

`EventPublisher` takes no correlation-ID parameter:

```java
public EventEnvelope<Object> buildEnvelope(String eventType, String aggregateId, UUID eventId, Object payload) {
    UUID correlationId = CorrelationIdHolder.get();
    if (correlationId == null) {
        throw new IllegalStateException("No correlationId in scope while publishing " + eventType
                + " — every publish site must run within an HTTP request or a @KafkaListener that set one");
    }
    return new EventEnvelope<>(eventId, eventType, EventTypes.CURRENT_VERSION,
            Instant.now(), correlationId, aggregateId, payload);
}
```

This is the trade the pattern makes. Threading the ID explicitly would mean adding a parameter to
every method between the entry point and the publish site — which is where most attempts at this
quietly die. Reading it from ambient scope keeps signatures clean, at the cost of an invisible
dependency on somebody having set it.

**The `IllegalStateException` is what makes that trade safe.** A publish site running outside any
scope fails loudly and immediately, naming the rule it broke, instead of writing an event with a null
correlation ID that nobody notices until a trace comes up empty three weeks later. An implicit
dependency with a loud failure is very different from an implicit dependency with a silent one.

## The failure modes

**Forgetting to clear.** `ThreadLocal`s on a pooled thread outlive the work that set them. The next
request or record handled by that thread inherits a stale ID, and the trace silently merges two
unrelated operations. Always clear in a `finally`; never in the happy path only.

**Losing it across an async boundary.** A `ThreadLocal` does not follow work handed to an executor,
a `CompletableFuture`, or a `@Async` method. It must be captured and re-established explicitly —
which is exactly what `runInScope` does, and what any thread hand-off has to do too.

**Setting the holder but not the MDC** (or the reverse). They are two independent `ThreadLocal`s.
Setting one gives you an ID in your code with nothing in the logs, or logs with an ID that
`EventPublisher` cannot see. Set and clear both, in one place — which is why `runInScope` exists
rather than callers doing it by hand.

## What it buys

`docker compose logs | grep <correlation-id>` returns every log line, from every service, belonging to
one order — in order.

The same value also lands in:

- **the `ApiError` response body**, so a user reporting a failure hands you the exact search term;
- **every event envelope**, so the Event Explorer can group a workflow's events;
- **structured log fields**, where the ECS encoder puts MDC entries under `labels.*` automatically —
  see [Chapter 8](../08-observability-and-scaling/README.md).

## Relationship to distributed tracing

This is a hand-rolled subset of what OpenTelemetry, Zipkin, or Micrometer Tracing provide — those add
spans, parent/child relationships, timing, and sampling, and propagate through W3C `traceparent`
headers automatically.

Building it by hand here is a deliberate scope decision: one field on an envelope and two
`ThreadLocal`s, versus a collector, a backend, and an agent. The honest framing is that this gives you
**correlation** but not **tracing** — you can find every line for one operation, but not a timing
waterfall showing where the time went.
