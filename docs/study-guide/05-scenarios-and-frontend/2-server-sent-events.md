# 5.2 — Server-Sent Events

[← The scenario service](1-the-scenario-service.md) · [Next: The eight scenarios →](3-the-eight-scenarios.md)

[Chapter 2](../02-domain/6-the-first-frontend.md) left the frontend polling every four seconds. This
is the replacement, and it is ADR-003.

---

## Why polling is not good enough

The frontend polls because an order's status changes without the client doing anything. That works
and it visibly quantizes time — which matters here more than usual, because
`frontend-design.md`'s Scenario Run Detail page is *"explicitly a live timeline: entries should
appear as the run progresses, with sub-second timestamps visible."*

ADR-003's rejection of polling is precise:

> polling visibly quantizes it: a 1.4-second run rendered from 1-second polls loses exactly the
> ordering detail the page exists to show.

The whole point of the timeline is to show that the HTTP 201 returned *before* the events that
fulfilled the order. Sample that at 1Hz and the asynchrony — the thing being demonstrated —
disappears.

## Why SSE and not WebSockets

> What the client needs is narrow: order status transitions, scenario progress, timeline entries, and
> occasional health changes. **All server-to-client.** The client's own actions — create an order, run
> a scenario — are ordinary REST calls that already have a natural request/response shape.

Nothing needs to travel the other way. And the design docs anticipated the temptation:

> WebSockets are acceptable if chosen deliberately, "but should not be added simply for resume keyword
> value."

ADR-003's own summary is the answer to give if asked:

> Choosing WebSockets here would be the resume-keyword decision the design doc warns against — and
> being able to explain *why not* is worth more in an interview than having used them.

> **Primer — [Server-Sent Events](../technology/http/server-sent-events.md)**
> The wire format, `EventSource` and automatic reconnection, SSE vs. WebSockets, the HTTP/1.1
> connection limit, keep-alives, `SseEmitter` mechanics and its four traps, and deployment
> considerations.

Two other rejections worth remembering. **Long polling** — strictly worse for this shape, with no
compensating advantage. **Kafka straight to the browser** — rejected because *"it would put topic
structure into the client, give the frontend a consumer group to manage, and make the browser a
participant in the messaging topology instead of an observer of the system's own API."*

And one thing deliberately **not** streamed:

> Health data uses ordinary polling of Actuator endpoints, not a stream: it changes rarely and a stale
> health tile is a much smaller problem than a stale order timeline.

Choosing the mechanism per use case rather than adopting one everywhere.

---

## Two streams

| Endpoint | Events | Closes |
|---|---|---|
| `GET /api/orders/stream` (Order Service) | `order-status-changed`, optionally filtered by `orderId` | Never; 30-minute emitter timeout |
| `GET /demo/scenario-runs/{runId}/stream` (Scenario Service) | `timeline-entry`, `run-status` | When the run finishes |

Both frozen in Phase 0, both declaring `text/event-stream` and naming their event types in the
contract.

---

## The order stream

`OrderEventStreamRegistry` is where four separate concerns meet, and each is worth understanding.

### Broadcast, not subscribe

```java
/**
 * docs/openapi/order-service.yaml's {@code orderId} query parameter is a per-connection filter, not
 * a per-topic subscription — there is no Kafka-style partitioning here, so the simplest correct
 * thing is to broadcast every transition to every connected emitter and let each connection's own
 * {@code orderId} filter (recorded at {@link #register}) decide whether to forward it. Documented
 * judgment call: with the handful of concurrent demo viewers this project expects, broadcasting is
 * simpler and no less correct than maintaining a per-order subscriber index.
 */
```

An O(connections) scan per transition instead of an index. For a handful of viewers that is nothing,
and the alternative is a second data structure to keep consistent as connections come and go.

**A documented judgment call with its scope stated** — *"the handful of concurrent demo viewers this
project expects"* — is much better than either an unexplained simple implementation or a premature
index. It also tells you exactly when to revisit it.

### Emitters clean themselves up

```java
emitter.onCompletion(() -> emitters.remove(emitter));
emitter.onTimeout(() -> { emitter.complete(); emitters.remove(emitter); });
emitter.onError(ex -> emitters.remove(emitter));
```

All three callbacks, always. A client that closes a tab or loses its network is pruned without
intervention.

And the timeout is a feature rather than a limit:

> Generous but bounded: EventSource reconnects automatically, so a periodic forced reconnect is
> harmless and keeps a stuck/half-open TCP connection from pinning an emitter forever.

**Because the client reconnects for free, the server can afford to be ruthless.** A half-open TCP
connection — one where the peer is gone but no FIN arrived — is otherwise invisible and permanent.

### Keep-alives

```java
private static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(15);
// …
emitter.send(SseEmitter.event().comment("keep-alive"));
```

A comment frame every 15 seconds on a dedicated daemon thread. Ignored by the parser, indistinguishable
from traffic to every proxy in between. An order stream can legitimately be silent for minutes, and
without this those connections would be closed by infrastructure and re-established on a cycle.

### Per-emitter synchronization

This is the interesting one, and it is a real bug that was really hit.

```java
synchronized (emitter) {
    try {
        emitter.send(SseEmitter.event().name("order-status-changed").data(message));
    } catch (IOException | IllegalStateException ex) {
        emitters.remove(emitter);
        completeWithErrorQuietly(emitter, ex);
    }
}
```

The class Javadoc states the hazard exactly:

> `SseEmitter#send` is not safe to call concurrently from multiple threads on the same emitter
> instance [...] any of Order Service's Kafka listener container threads (inventory/payment/fulfillment
> events each run on their own thread) can call `broadcast` for an unfiltered connection at roughly
> the same moment, and the keep-alive tick runs on yet another, independent thread on a fixed schedule
> [...] two threads' calls to the same `SseEmitter`'s underlying writer can interleave mid-write and
> corrupt the SSE byte stream — **observed as a client-side parser reconstructing a garbled or
> duplicated event.**

Note the symptom: **no server-side error at all.** The server logs look perfect. The bug appears in
the browser as a corrupted event, which is the kind of thing you spend a long time blaming on the
client.

Note also *what* is synchronized: **the emitter instance**, not the registry. One connection's writes
serialize; other connections are unaffected. A global lock would let one slow client block delivery to
everyone.

> **We got this wrong.** This is the SSE-under-concurrency defect from Sprint 2 goal 2. Three
> independent Kafka listener threads plus a scheduled keep-alive is four writers per connection, and
> the original implementation had no synchronization at all.
> [Chapter 10](../10-retrospective/README.md).

### Cleanup that can itself fail

```java
/**
 * {@code SseEmitter#completeWithError} can itself throw once the client's connection has broken
 * badly enough that the async context is no longer usable [...] letting a second exception escape
 * here does not just fail to clean up one dead SSE connection — it fails that unrelated caller's own
 * HTTP request (e.g. a {@code POST /api/orders} whose transaction had already committed
 * successfully).
 */
private void completeWithErrorQuietly(SseEmitter emitter, Exception cause) {
    try {
        emitter.completeWithError(cause);
    } catch (RuntimeException cleanupEx) {
        log.debug("Ignoring failure while completing an already-broken SSE emitter", cleanupEx);
    }
}
```

This is the subtlest bug in the project, and the causal chain is worth following slowly.

`broadcast` runs on the thread that produced the event — for a status change, the thread that just
committed the business transaction, because `OrderStatusStreamListener` is a
`@TransactionalEventListener` running in the caller's own thread.

So: someone's `POST /api/orders` commits successfully. The commit triggers a broadcast **on that same
thread**. One connected SSE client happens to be dead. The send throws, the cleanup throws again, and
the second exception propagates up through the broadcast into the POST's own request handling.

**A dead SSE connection belonging to a completely unrelated viewer fails a successful order creation.**
Coupling through a shared thread that nothing in either piece of code makes visible.

> **We got this wrong.** Found in Sprint 2's bug hunt under a high-volume concurrent-SSE-fan-out test.
> [Chapter 10](../10-retrospective/README.md).

The general lesson: **cleanup code on an error path must not be able to throw.** It runs when things
are already broken, which is exactly when the assumptions it relies on do not hold.

And the companion fix in `GlobalExceptionHandler` from
[Chapter 2](../02-domain/3-the-http-layer.md) — the `void` handler for
`AsyncRequestNotUsableException`, because no JSON error body can be written onto a committed
`text/event-stream` response.

---

## The run stream

`RunEventHub` is the same idea with a different keying:

```java
private final Map<String, List<SseEmitter>> emittersByRun = new ConcurrentHashMap<>();

public SseEmitter subscribe(String runId) {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    List<SseEmitter> emitters = emittersByRun.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
    // …the same three callbacks
}
```

Indexed **by run** rather than broadcast, because a run ID is a natural partition and a viewer only
ever cares about one. `CopyOnWriteArrayList` because the list is read on every emit and written only
when a viewer connects or leaves.

The discipline that matters is the ordering:

> Emits `timeline-entry` for every timeline row as it is actually persisted, and `run-status` on a
> status change — **never before the underlying write has committed**, matching the same
> real-time-only-after-it's-real discipline the OpenAPI doc asks for.

And `TimelineRecorder` enforces it:

> Appends one timeline entry at a time, persists it, then pushes it over SSE — in that order, so a
> subscriber never sees an entry that isn't durable yet.

**Persist, then publish.** A subscriber that sees an entry and then refreshes must not find it gone. It
is the same ordering constraint the transactional outbox exists to enforce for Kafka
([Chapter 6](../06-outbox/README.md)) — here the stream is not durable, so publishing *after* the
commit is sufficient.

`TimelineRecorder` also handles concurrency explicitly:

> Two independent threads append to the same run concurrently in general (the scenario harness thread
> for HTTP/STATE_CHANGE entries, and one or more Kafka listener threads for EVENT entries), so
> sequence assignment is synchronized per run id.

Per **run**, not globally — the same instinct as synchronizing per emitter.

---

## The client side

```ts
export function subscribeToStream(
  url: string,
  handlers: { onMessage: (eventName: string, data: string) => void; onOpen?: () => void; onError?: (event: Event) => void },
  eventNames: string[],
): () => void {
  const source = new EventSource(url);
  const listeners = eventNames.map((name) => {
    const listener = (event: MessageEvent) => handlers.onMessage(name, event.data);
    source.addEventListener(name, listener as EventListener);
    return { name, listener };
  });
  return () => {
    for (const { name, listener } of listeners) {
      source.removeEventListener(name, listener as EventListener);
    }
    source.close();
  };
}
```

Native `EventSource`, no library — a pinned decision. And the function's own Javadoc names its scope:

> Deliberately dumb: no reconnection/backoff policy beyond what EventSource itself does (it
> auto-reconnects on a dropped connection by default), no buffering. Callers that need a polling
> fallback [...] wire that themselves via `onError`.

**It returns an unsubscribe function**, which is exactly the shape React's `useEffect` cleanup wants:

```tsx
useEffect(() => subscribeToStream(url, handlers, ['order-status-changed']), [url]);
```

Without that, every remount leaks a connection — and with a 6-connection-per-origin browser limit, a
leak becomes a hang rather than a slowdown. See the
[React primer](../technology/react/components-and-hooks.md) on effect cleanup.

---

## What SSE does not solve

ADR-003 records the costs, and two are real:

> SSE is one-directional by definition. If a genuine need for client-to-server streaming appears, this
> decision has to be revisited rather than extended.

> Each open stream holds a server connection [...] it interacts with Kubernetes rolling updates (a pod
> restart drops streams, and `EventSource` reconnects to whichever pod it lands on).

That second one is not hypothetical — it is exactly what
[Chapter 9](../09-production/README.md) runs into on the deployed demo.

---

[← The scenario service](1-the-scenario-service.md) · [Next: The eight scenarios →](3-the-eight-scenarios.md)
