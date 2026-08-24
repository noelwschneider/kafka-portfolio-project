# Chapter 5 — The scenario engine and the live frontend

**Build history:** Phase 5 — `b363d42 add scenario service and demo frontend` (and `0d7b4ea`, its
follow-up).

The chapter where the project becomes something you can show someone. Four services and a Kafka
cluster are not a portfolio piece; a page where a stranger clicks "Poison Message" and watches a real
record fail, retry, and land in a dead-letter topic is.

Phase 5's exit criterion is the demanding one:

> A reviewer can understand and exercise the system without reading the source code.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [The scenario service](1-the-scenario-service.md) | Why a fifth service exists, the run lifecycle, `@Async` and the proxy trap, the runner abstraction and toolkit, observing real outcomes instead of sleeping |
| 2 | [Server-Sent Events](2-server-sent-events.md) | ADR-003 and why not WebSockets, the two streams, `SseEmitter` mechanics, per-emitter synchronization, cleanup that can itself throw, the client wrapper |
| 3 | [The eight scenarios](3-the-eight-scenarios.md) | Each scenario, what it demonstrates, and how it stays real; `POST /demo/reset` and why it needed its own inventory operation |
| 4 | [Observing the system](4-observing-the-system.md) | The event projection and its honesty boundary, real consumer lag from the admin API, the interleaved run timeline, System Health |
| 5 | [The console](5-the-console.md) | React Router and why it arrived now, query-client defaults tuned for a console, and the Mermaid renderer's two real bugs |

---

## The fifth service

The Phase 0–3 story is about four services. This chapter adds a fifth that owns **no business data**,
participates in **no workflow**, and exists entirely because
[ADR-002](../01-design-contract/3-state-and-api-contracts.md) refused to let a `forcePaymentFailure`
flag onto the order API.

It is also the largest module in the repository — 71 Java files against Order Service's 56. The
demonstration is bigger than any one domain, which is exactly what you would expect of a project whose
product is the demonstration.

---

## Two ideas worth carrying out

**Adding an observer costs the observed nothing.** The event projection reads all eight topics in its
own consumer group. No producer changed, no consumer noticed, no delivery was diverted. That is the
log-versus-queue property from [Chapter 1](../01-design-contract/1-boundaries-and-ownership.md)
paying for itself in a feature nobody designed for in Phase 0.

**Refusing to display what you cannot observe.** The Event Explorer shows publication but not
consumption, because consumption happens inside another service's transaction against a schema this
one may not read. A fabricated "consumed at, 43ms, 0 retries" would look better and be false. Being
able to explain what a page deliberately omits, and why, is a stronger answer than a richer page you
cannot defend.

---

## Build it yourself

**Scenario Service** — [section 1](1-the-scenario-service.md)

1. A fifth module, port 8085, schema `scenario_service`. `spring-boot-starter-web`, `-data-jpa`,
   `-kafka`, plus the `common` dependency.
2. `V1__scenario_runs.sql` (`scenario_runs`, `scenario_run_timeline`) and `V2__events.sql`.
3. HTTP clients for the other four services, base URLs from configuration
   (`ServiceUrlsProperties`), built on `RestClient`.
4. `ScenarioRunner` interface, `ScenarioToolkit` parameter object, `AbstractScenarioRunner` with
   `createOrder` and `recordHttp` helpers that record the **real** status code.
5. `ScenarioExecutionService` (validate, mint `runId` + `correlationId`, persist, return) and a
   **separate** `ScenarioRunExecutor` bean carrying `@Async` — self-invocation would run it
   synchronously.
6. `CorrelationIdHolder.runInScope` around the whole run, logging the start line **inside** the scope.
7. `OrderStatusWatcher` polling the real order API; `ConsumerLagService` over `AdminClient`, failing
   soft.
8. Controllers under `/demo` only: `/demo/scenarios`, `/demo/scenario-runs`, `/demo/events`,
   `/demo/reset`.

**SSE** — [section 2](2-server-sent-events.md)

9. `GET /api/orders/stream` returning an `SseEmitter`, backed by a registry that broadcasts with a
   per-connection `orderId` filter, registers all three lifecycle callbacks, runs a 15-second
   keep-alive on a daemon thread, **synchronizes every send on the emitter instance**, and wraps
   `completeWithError` so cleanup cannot throw.
10. `GET /demo/scenario-runs/{runId}/stream` backed by `RunEventHub`, keyed by run, emitting
    `timeline-entry` and `run-status` — **only after the underlying write has committed**.
11. `TimelineRecorder`: persist, then publish; per-run synchronized sequence assignment; `detail` as
    an open map containing only observed fields.
12. The `void` `AsyncRequestNotUsableException` handler in `GlobalExceptionHandler`.
13. Client: a `subscribeToStream` wrapper over native `EventSource` returning an unsubscribe function,
    used as a `useEffect` cleanup.

**Scenarios** — [section 3](3-the-eight-scenarios.md)

14. Eight `ScenarioRunner` components, plus a `ScenarioCatalog` of definitions.
15. `POST /demo/reset` — seed inventory, clear demo state, resume every consumer, clear payment
    behavior — **reporting what it actually reset**.
16. `restoreForDemo` on Inventory Service, zeroing `reserved_quantity` and `available_quantity`
    together, bypassing the business guard that reset would otherwise trip.
17. `IdleResetScheduler`, defaulting to 15 minutes.

**Observation** — [section 4](4-observing-the-system.md)

18. `EventProjectionConsumer` on all eight topics in its **own** consumer group, recording only
    publication facts — no fabricated consumption phase.
19. `EventQueryService` and a paged `GET /demo/events`.

**Console** — [section 5](5-the-console.md)

20. React Router with seven top-level routes plus two nested detail routes; keep the existing pages
    unchanged behind thin route wrappers.
21. `QueryClient` defaults of `retry: 0` and `networkMode: 'always'`.
22. `MermaidDiagram` with a dynamic `import('mermaid')`, module-level one-time initialization, and a
    shared promise chain serializing every render.

**Done when:** a reviewer with only the URL can run all eight scenarios, watch each run's interleaved
timeline update live, browse every event with real topic/partition/offset, see a DLQ record's failure
metadata, pause a consumer and watch lag climb and drain, and read the architecture — without opening
the source.

---

## Next

[Section 1 — The scenario service](1-the-scenario-service.md).
