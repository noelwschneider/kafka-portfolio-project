# Sprint 2 goal 7 — Bug hunt (concurrency and partial-failure paths)

Time-boxed pass, focused on concurrency and partial-failure paths per the goal 7 brief — not an
exhaustive audit. All testing was against the real `docker compose` stack (all 5 services, real
Postgres, real Kafka), not against mocks or a read of the code alone. `docker compose up --build`
was used instead of `kind`: none of scenario-service's fault-injection scenarios touch the
Kubernetes API (verified — no `KubernetesClient`/`kubectl` usage anywhere under
`services/scenario-service/src/main`), so compose exercises the same application-level failure
paths without the extra cluster-bring-up cost. All dev infra (`docker compose down`) was torn down
before finishing.

## Defects found

### 1. Unmapped routes returned HTTP 500 instead of 404 (fixed)

**Starting item**, already flagged in `docs/agent-reports/sprint-2/deployment-execution-report.md`
item 4.

**Repro:** `curl http://localhost:<port>/this-path-does-not-exist` against each of the 5 backend
services (order 8081, inventory 8082, payment 8083, fulfillment 8084, scenario 8085) — every one
returned `{"status":500,"code":"INTERNAL_ERROR",...}`.

**Root cause:** `services/common/src/main/java/com/orderfulfillment/common/GlobalExceptionHandler.java`
is a `@RestControllerAdvice` shared by all 5 services. Its only handlers were `ApiException`,
`MethodArgumentNotValidException`, `HttpMessageNotReadableException`, and a catch-all
`@ExceptionHandler(Exception.class)`. Spring's `DispatcherServlet` throws
`org.springframework.web.servlet.resource.NoResourceFoundException` for any request path matching
no `@RequestMapping` and no static resource (Spring Boot 4.1 / Spring 6.1+ default behavior). With
no dedicated handler for it, it fell through to the catch-all and was reported as `INTERNAL_ERROR`
/ 500, logged at ERROR — both a wrong status code and log noise that could mask a genuine failure.
Confirmed via container logs (`error.type` in the ECS-structured log line).

**Severity:** Low blast radius (wrong status code, no data effect, self-contained to HTTP response
generation) but affects every unmapped request to every service — a routine client mistake looked
like a server crash, and every occurrence was logged at ERROR, polluting the error-rate signal this
project's own observability stack (Prometheus/Grafana) would use to detect real problems.

**Fix:** Added a dedicated `@ExceptionHandler(NoResourceFoundException.class)` returning 404 with
code `NOT_FOUND`, in `services/common/src/main/java/com/orderfulfillment/common/GlobalExceptionHandler.java`.

**Test:** `services/order-service/src/test/java/com/orderfulfillment/order/UnmappedRouteIntegrationTest.java#unmappedPathReturns404NotInternalServerError` —
real `RestTestClient` request against the real running Spring context (Testcontainers Postgres +
Kafka), asserting 404 and the `NOT_FOUND` code. Since the fix lives in the shared `common` module,
it applies to all 5 services identically; re-verified live post-fix with the same `curl` repro
against all 5 ports (all returned 404).

### 2. Wrong HTTP method on a real route also returned 500 instead of 405 (fixed)

**Found while reproducing defect 1** — an incorrect manual test (`POST /demo/scenario-runs`,
which is GET-only) against scenario-service returned 500 rather than a routing error, surfacing a
second instance of the same underlying pattern.

**Repro:** `curl -X POST http://localhost:8085/demo/scenario-runs` → 500. Container logs showed
`org.springframework.web.HttpRequestMethodNotSupportedException: Request method 'POST' is not
supported`, again falling through the same catch-all.

**Severity:** Same class and severity as defect 1 — wrong status code and ERROR-level log noise for
a routine client error, no data effect.

**Fix:** Added `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` returning 405 with
code `METHOD_NOT_ALLOWED`, same file as defect 1.

**Test:** `UnmappedRouteIntegrationTest#wrongHttpMethodOnRealRouteReturns405NotInternalServerError` —
`POST /api/orders/stream` (a real, GET-only route in Order Service) against the live test context,
asserting 405. Re-verified live post-fix across the rebuilt stack.

### 3. A dead SSE client could fail an unrelated, already-committed HTTP request (fixed)

**Found live** while stress-testing the documented SSE-under-concurrency issue referenced in the
goal 7 brief (`OrderStatusWatcher`'s Javadoc, `docs/agent-reports/phase-10-scaling-demo.md`).

**Repro:** Opened ~80–100 concurrent `GET /api/orders/stream` connections against Order Service via
`curl -N -m <timeout>`, then fired ~40–60 concurrent `standard-order` scenario runs (each creates a
real order) while those connections were timing out/dropping under load. Container logs showed:

```
ERROR com.orderfulfillment.common.GlobalExceptionHandler — Unexpected error handling POST /api/orders
org.springframework.web.context.request.async.AsyncRequestNotUsableException:
    ServletResponse failed to flushBuffer: java.io.IOException: Broken pipe
    at ...OrderEventStreamRegistry.lambda$broadcast$4(OrderEventStreamRegistry.java:111)
    at ...OrderEventStreamRegistry.broadcast(OrderEventStreamRegistry.java:102)
    at ...OrderStatusStreamListener.onOrderStatusChanged(...)
    at ...TransactionalApplicationListenerSynchronization$PlatformSynchronization.afterCompletion(...)
    at ...AbstractPlatformTransactionManager.commit(...)
    at ...OrderPersistence$$SpringCGLIB$$0.createPendingOrder(...)
    at ...OrderService.createOrder(...)
    at ...OrderController.createOrder(...)
```

The 500 was attributed to `POST /api/orders` — a request whose own transaction had already
committed successfully — not to the dead SSE connection that actually caused it.

**Root cause:** `OrderEventStreamRegistry.broadcast()` iterates every registered emitter and, on
send failure, catches `IOException | IllegalStateException` and calls `emitter.completeWithError(ex)`
to clean up. `broadcast()` is invoked synchronously as a `@TransactionalEventListener`
(`OrderStatusStreamListener`) on `afterCommit` — i.e. on whatever thread committed the business
transaction that produced the event, which for order creation is the `POST /api/orders` request
thread itself, not a background thread. `AsyncRequestNotUsableException` does extend `IOException`
so the initial `send()` failure was caught correctly, but `emitter.completeWithError(ex)` — called
*inside* that catch block, on an emitter whose connection had broken badly enough (TCP reset under
concurrent load) — could itself throw. An exception thrown inside a `catch` block is not caught by
that same `catch`, so it propagated out of `broadcast()`, out of the transaction-commit listener,
and into whatever unrelated request happened to be committing at that moment — turning a dead SSE
client's disconnect into a spurious 500 on someone else's already-succeeded order creation.

This is a more severe variant of the already-documented defect (`OrderStatusWatcher`'s Javadoc
described only the dead connection's own status trace getting corrupted); this pass found that the
blast radius extends to unrelated callers.

**Severity:** Medium. No data corruption — the business transaction had already committed, so the
order was created correctly despite the 500 — but the caller receives a false failure signal for a
request that actually succeeded, which for a real client could trigger a spurious retry (itself
harmless here since order creation isn't idempotent-by-client-request, but still a wrong signal) and
is the kind of intermittent, load-dependent 500 that's hard to trace back to its real cause without
exactly this kind of investigation.

**Fix:** `services/order-service/src/main/java/com/orderfulfillment/order/OrderEventStreamRegistry.java` —
wrapped the `completeWithError` cleanup call (in both `broadcast()` and `sendKeepAlive()`, which had
the identical pattern) in its own `try/catch(RuntimeException)` that logs at DEBUG and swallows,
since the emitter is already removed from the registry before cleanup runs regardless of outcome.
Small, localized, boring — no change to the send/synchronization logic that already correctly
serializes per-emitter writes (that part was already fixed in a prior pass per the class Javadoc).

**Test:**
`services/order-service/src/test/java/com/orderfulfillment/order/OrderStreamBrokenConnectionIntegrationTest.java` —
opens a real raw-socket SSE connection, forces a TCP reset (`SO_LINGER(0)` then close, reproducing a
genuinely broken pipe rather than a clean disconnect), then creates 5 real orders and asserts every
one returns 201/PENDING. Re-verified live: rebuilt the image, re-ran the exact concurrent-load repro
(100 SSE connections + 60 concurrent order creations) against the rebuilt container — zero
`AsyncRequestNotUsableException` occurrences and zero ERROR-level log lines in that window,
versus repeated occurrences before the fix under the identical load.

## What was tested and found clean (no defect, no fix needed)

- **Inventory contention** (`docs/scenarios.md` / scenario-service's `inventory-contention`
  scenario): ran live against real SKU-004 stock. Two concurrent orders competed for the last
  units; one reached `FULFILLED`, one reached `REJECTED_OUT_OF_STOCK`, inventory version advanced
  by exactly 1, no oversell. `InventoryService.reserve()`'s optimistic-lock retry loop
  (`services/inventory-service/src/main/java/com/orderfulfillment/inventory/InventoryService.java`)
  and its existing test coverage (`InventoryConcurrencyIntegrationTest`,
  `InventoryKafkaConcurrencyIntegrationTest`, `InventoryServiceOptimisticLockTest`) already cover
  this well; nothing new found.
- **Payment-service outage mid-saga**: created a real order, `docker stop`'d payment-service
  immediately after (order caught at `PAYMENT_PENDING`), left it down 15s, `docker start`'d it back
  up. The order resumed and reached `FULFILLED` with a complete, correctly-ordered status history —
  matching the ADR-006/reliability-pattern.md design (Kafka durably holds the event; the consumer
  resumes from its last offset on restart). No stranding, no duplicate side effects.
- **`consumer-outage` and `poison-message` scenarios**: ran both live end-to-end via
  `POST /demo/scenarios/{name}`; both completed (`status: COMPLETED`) with the expected timeline
  shape (pause → publish → resume → drain for the first; DLQ landing for the second).
- **`IdleResetScheduler` / `DemoResetService`** (the reset/idle-scheduler code touched earlier this
  sprint): read through for other races near the just-landed fix, per the brief's instruction not to
  re-litigate that fix itself. `@Scheduled` methods run single-threaded by default, so there's no
  intra-scheduler race; the one real race (a scenario starting between the idle guard check and
  `reset()`) is already handled via the `ConflictException`/409 catch, treated as benign. Did not
  find a new issue here; did not exhaustively re-review `DemoResetService`'s internals beyond that —
  see "not covered" below.

## What was deliberately not covered (time-boxed; honest gaps, not failures)

- **`HttpMediaTypeNotSupportedException` / `HttpMediaTypeNotAcceptableException`**: same
  catch-all-swallows-a-specific-Spring-exception pattern as defects 1 and 2 might also apply to
  unsupported/unacceptable content types. Not reproduced or fixed — flagged here as a likely small
  follow-up in the same file, same pattern, if it turns out to matter.
- **Kafka consumer rebalance mid-transaction**: not exercised (would need multiple consumer
  instances/partition reassignment mid-flight, which single-replica compose doesn't naturally
  produce without deliberately scaling a service — out of the time box).
- **`DemoResetService` internals beyond the scheduler's own guard**: read but not stress-tested
  under concurrent manual `POST /demo/reset` + idle-triggered reset racing each other.
- **ADR-009's out-of-order transition guard**: not re-tested live; it already has a dedicated,
  deterministic reproduction test (`OrderOutOfOrderTransitionIntegrationTest`) from when it was
  built, and re-deriving that race live was judged lower-yield than the SSE and route-handling
  issues actually found.
- **Inventory-service, payment-service, fulfillment-service SSE or streaming equivalents**: Order
  Service is the only service with an SSE endpoint, so defect 3's fix pattern doesn't need
  replicating elsewhere — confirmed via `grep -rl "SseEmitter" services/*/src/main`.
- **Broader fuzzing of unmapped/malformed requests** (query param injection, oversized bodies,
  malformed JSON edge cases beyond the existing `HttpMessageNotReadableException` handler): out of
  scope for this pass's concurrency/partial-failure focus.

## Files touched

- `services/common/src/main/java/com/orderfulfillment/common/GlobalExceptionHandler.java` — defects
  1 and 2.
- `services/order-service/src/main/java/com/orderfulfillment/order/OrderEventStreamRegistry.java` —
  defect 3.
- `services/order-service/src/test/java/com/orderfulfillment/order/UnmappedRouteIntegrationTest.java` — new, defects 1 and 2.
- `services/order-service/src/test/java/com/orderfulfillment/order/OrderStreamBrokenConnectionIntegrationTest.java` — new, defect 3.

No frozen contract (`docs/openapi/`, `docs/events/`, `docs/order-state-machine.md`,
`docs/db-ownership.md`) was touched — all three fixes are internal error-handling/robustness
changes with no change to any documented request/response shape or event contract. The full
order-service test suite (30 tests) and a multi-module build of all 5 services pass after these
changes.
