# ADR-003: Use SSE rather than WebSockets for live frontend updates

- **Status:** Accepted. Endpoints frozen in Phase 0; implemented in Phase 5.
- **Date:** 2026-08-17 (Phase 0)

## Context

Because the order workflow is asynchronous (ADR-001), `POST /api/orders` returns `PENDING` and every
interesting thing happens afterwards. A frontend that only polled would either lag behind the
workflow or hammer the API, and `docs/planning/sprint-1/frontend-design.md`'s Scenario Run Detail page is
explicitly a live timeline: entries should appear as the run progresses, with sub-second timestamps
visible.

What the client needs is narrow: order status transitions, scenario progress, timeline entries, and
occasional health changes. All server-to-client. The client's own actions — create an order, run a
scenario — are ordinary REST calls that already have a natural request/response shape.

`docs/planning/sprint-1/frontend-design.md`'s Live Frontend Updates section states the preference for SSE and
adds a constraint worth honoring: WebSockets are acceptable if chosen deliberately, "but should not be
added simply for resume keyword value."

## Decision

Server-Sent Events, consumed with the browser's native `EventSource` — no client library
(`docs/planning/project-overview.md`'s Pinned Technology Decisions table).

Two streams are frozen in Phase 0:

- `GET /api/orders/stream` on Order Service — `order-status-changed` events, optionally filtered to
  one order (`docs/openapi/order-service.yaml`).
- `GET /demo/scenario-runs/{runId}/stream` on Scenario Service — `timeline-entry` and `run-status`
  events, closing when the run finishes (`docs/openapi/scenario-service.yaml`).

Both declare `text/event-stream`, send periodic keep-alive comments, and name their event types in the
contract. The per-message JSON schemas are deliberately deferred to Phase 2, alongside the event
payload schemas.

Health data uses ordinary polling of Actuator endpoints, not a stream: it changes rarely and a stale
health tile is a much smaller problem than a stale order timeline.

## Alternatives considered

**WebSockets** (Spring's STOMP support or raw). Bidirectional, and the reflexive choice for "live
updates". Rejected because nothing in the product needs client-to-server messaging over a persistent
socket, and the costs are real: a handshake protocol to get wrong, a client library to add, subprotocol
and heartbeat concerns, no automatic reconnect without writing one, and proxy configuration that has
to permit upgrades. `EventSource` reconnects on its own, and SSE is plain HTTP that any intermediary
already understands. Choosing WebSockets here would be the resume-keyword decision the design doc
warns against — and being able to explain *why not* is worth more in an interview than having used
them.

**Polling `GET /api/orders/{orderId}` on a short interval.** Trivially simple, no new endpoint, and
TanStack Query supports it in one option. Rejected as the primary mechanism because the scenario
timeline is the project's centerpiece and polling visibly quantizes it: a 1.4-second run rendered from
1-second polls loses exactly the ordering detail the page exists to show. Polling remains the
fallback if a stream drops and as the mechanism for health.

**Long polling.** Works everywhere, no new protocol. Rejected as strictly worse than SSE for this
shape — one response per event, connection churn, and more server-side plumbing to hold requests open
— with no compensating advantage now that `EventSource` is universally available in target browsers.

**Kafka straight to the browser** (a websocket bridge over a consumer, or a Kafka HTTP proxy).
Rejected: it would put topic structure into the client, give the frontend a consumer group to manage,
and make the browser a participant in the messaging topology instead of an observer of the system's
own API.

## Consequences and tradeoffs

**Accepted costs.**

- SSE is one-directional by definition. If a genuine need for client-to-server streaming appears, this
  decision has to be revisited rather than extended — the streams above would not grow into it.
- Each open stream holds a server connection. Fine for a demo with a handful of viewers; it is a real
  capacity consideration if this were ever multi-tenant, and it interacts with Kubernetes rolling
  updates (a pod restart drops streams, and `EventSource` reconnects to whichever pod it lands on).
- With multiple Order Service replicas, a status change observed by one pod must reach a client
  streaming from another. Phase 5 must handle that — the simplest honest option is that each replica
  consumes the lifecycle topic and serves its own connected clients, which works because every replica
  sees every event. Ignoring it would produce a stream that silently misses updates.
- No message replay: a client that connects mid-run misses earlier entries and must fetch
  `GET /demo/scenario-runs/{runId}` for the backlog, then stream the remainder. Both endpoints exist
  for exactly this reason.

**What it buys.**

- No frontend dependency for live updates at all — `EventSource` is in the browser.
- Plain HTTP: curl-able, visible in browser devtools as a normal request, and legible to any proxy or
  ingress without special configuration.
- Automatic client reconnection, including after the pod restarts that Kubernetes demonstrations
  cause.
