# Server-Sent Events

*Referenced from [Chapter 5.2 — Server-Sent Events](../../05-scenarios-and-frontend/2-server-sent-events.md).*

---

## What it is

A one-way stream from server to browser over an ordinary HTTP response that is never closed. The
server sets `Content-Type: text/event-stream` and writes text frames as things happen; the browser
parses them and fires events.

```
event: order-status-changed
data: {"orderId":"order-21873","status":"PAID"}

: keep-alive

event: order-status-changed
data: {"orderId":"order-21873","status":"FULFILLED"}

```

The wire format is deliberately trivial:

- `event:` — the event name the client listens for (defaults to `message`).
- `data:` — the payload. Multiple `data:` lines are joined with newlines.
- `id:` — an optional event ID the browser remembers.
- `retry:` — reconnect delay in milliseconds.
- A line starting with `:` is a **comment**, ignored by the parser — which is how keep-alives are
  sent.
- A **blank line** terminates a frame. Forgetting it means the client never fires.

## The client

```js
const source = new EventSource('/api/orders/stream?orderId=order-21873');
source.addEventListener('order-status-changed', (e) => {
  const data = JSON.parse(e.data);   // always a string; parsing is yours
});
source.addEventListener('error', (e) => { /* reconnecting, or dead */ });
source.close();
```

Built into every modern browser. No library, no protocol negotiation.

**Automatic reconnection is the headline feature.** If the connection drops, the browser waits (the
`retry:` interval, default a few seconds) and reconnects on its own. If the server has been sending
`id:` values, the browser sends the last one back as a `Last-Event-ID` header, so a server that wants
to can resume from it.

That is the single biggest practical difference from WebSockets, where reconnection is your problem.

## SSE vs. WebSockets

| | SSE | WebSockets |
|---|---|---|
| Direction | Server → client only | Bidirectional |
| Protocol | Plain HTTP | Upgrade handshake, own framing |
| Reconnection | Automatic | You implement it |
| Client library | None needed | Usually one |
| Payload | UTF-8 text | Text or binary |
| Proxies / infrastructure | Ordinary HTTP; works everywhere | Must permit upgrades |
| Connections per origin (HTTP/1.1) | ~6 browser limit | Not affected |

**Choose SSE when everything flows one way.** Live status, notifications, log tails, progress. The
client's own actions can be ordinary REST calls, which already have a natural request/response shape.

**Choose WebSockets when the client genuinely streams too** — chat, collaborative editing, games,
anything where the client sends a high-frequency stream rather than occasional commands.

The honest failure mode is picking WebSockets reflexively because "live updates" sounds like they
require it, and then owning a reconnect implementation, a heartbeat protocol, and proxy configuration
you did not need.

## The HTTP/1.1 connection limit

Browsers allow roughly **6 concurrent connections per origin** over HTTP/1.1, and an open SSE stream
occupies one for its whole life. Two streams plus normal API traffic is fine; a page opening six
streams will hang on the seventh request with no error, which is a memorable afternoon.

HTTP/2 multiplexes and effectively removes the limit. Worth knowing which one your deployment
actually serves.

## Keep-alives

Idle connections get closed — by proxies, load balancers, and NAT timeouts, typically after 30–60
seconds of silence. A stream that is legitimately quiet looks identical to a dead one.

The fix is a periodic comment frame:

```
: keep-alive

```

Ignored by the parser, indistinguishable from traffic to everything in between. Every 15–30 seconds is
typical. Without it, a quiet stream drops and reconnects on a cycle — which mostly works, and quietly
wastes connections and loses anything that happened during the gap.

## Server-side: Spring's `SseEmitter`

```java
@GetMapping(path = "/stream", produces = "text/event-stream")
public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    registry.add(emitter);
    emitter.onCompletion(() -> registry.remove(emitter));
    emitter.onTimeout(() -> { emitter.complete(); registry.remove(emitter); });
    emitter.onError(ex -> registry.remove(emitter));
    return emitter;
}

// later, from any thread:
emitter.send(SseEmitter.event().name("order-status-changed").data(payload));
```

Returning an `SseEmitter` releases the servlet thread and leaves the response open. Sends happen later,
from whatever thread has something to say.

Four things to get right:

**`SseEmitter#send` is not thread-safe per emitter.** Spring's own Javadoc says so. Two threads
writing to one emitter can interleave mid-write and corrupt the byte stream — which surfaces as a
client-side parse error or a garbled event, not as a server exception. Synchronize per emitter (not
globally, or one slow client blocks every other).

**Always set a timeout, and always register all three callbacks.** An emitter that is never removed
from your registry is a leak, and dead emitters accumulate silently — you only find out when sends
start failing.

**A send to a disconnected client throws** `IOException` (broken pipe) or `IllegalStateException`
(already completed). Both mean "this client is gone." Remove it and move on; neither is an error worth
logging above `DEBUG`.

**Cleanup can itself throw.** `completeWithError` on a connection that is already unusable can raise a
second exception. If your send loop runs on a thread that is doing something else important, letting
that escape breaks the unrelated work. Wrap cleanup in its own try/catch.

## The error-handling trap

Once a response is committed as `text/event-stream`, **no JSON error body can be written to it**. A
framework exception handler that tries will fail a second time — typically with something like
"no converter for [ApiError] with preset Content-Type 'text/event-stream'" — logged on top of the
original failure and obscuring it.

The fix is a handler that writes nothing at all. In Spring MVC, a `void` return tells the framework
the response is fully handled:

```java
@ExceptionHandler(AsyncRequestNotUsableException.class)
public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex, HttpServletRequest request) {
    log.debug("Client connection is already gone for {} {}", request.getMethod(), request.getRequestURI(), ex);
}
```

A client disconnecting mid-stream is routine, not a server error. `DEBUG`, not `ERROR`.

## Deployment considerations

- **Buffering proxies break SSE.** nginx buffers responses by default and will hold your frames until
  a buffer fills. `proxy_buffering off;` (or `X-Accel-Buffering: no`) is required.
- **Read timeouts must exceed your keep-alive interval**, or the proxy closes the connection between
  keep-alives.
- **A rolling deployment drops every stream.** `EventSource` reconnects, and lands on whichever
  instance the load balancer picks — so any state you hold per connection must be re-establishable, or
  the client must be able to resync after a gap.
- **Compression is usually wrong here.** Buffering compressors defeat the point; disable it for the
  stream endpoint.
