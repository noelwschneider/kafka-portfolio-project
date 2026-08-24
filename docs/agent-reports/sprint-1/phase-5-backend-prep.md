# Phase 5 Report — Backend Prep for the Scenario-Oriented Frontend

**Date:** 2026-08-18
**Scope:** two small pieces of backend groundwork that block Phase 5's frontend workstream and the
new Scenario Service, but belong to neither: (1) implement `GET /api/orders/stream` (SSE) in Order
Service, and (2) add Spring Boot Actuator health to all four backend services. Confined to
`services/order-service/`, `services/inventory-service/`, `services/payment-service/`,
`services/fulfillment-service/` — `services/common/`, `services/scenario-service/`, and `frontend/`
were not touched (two sibling agents own those, working concurrently in the same tree).

**Result:** all four services' full test suites green (order-service 20 tests, inventory-service 23
tests, payment-service 11 tests, fulfillment-service 8 tests — 0 failures, 0 errors across the
board), plus a live end-to-end run against real Docker infrastructure with all four services running
together.

---

## 1. Part 1 — `GET /api/orders/stream` (SSE)

### What was built

| File | What |
|---|---|
| `OrderStatusChangedEvent.java` | New. Plain internal Spring application event (not a Kafka record) — `orderId`, `status`, `previousStatus`, `sourceEventId`, `correlationId`, `occurredAt`. |
| `OrderPersistence.java` | Each of the five `@Transactional(REQUIRES_NEW)` methods now publishes `OrderStatusChangedEvent` immediately after its `historyRepository.save(...)` call, via a new `publishStatusChanged` helper. `writeStatus` (the shared private method four of the five methods route through) now also captures the order's `previousStatus` before overwriting it. `correlationId` is read from `CorrelationIdHolder.get()` — already bound on the thread by `CorrelationIdFilter` (HTTP) or `CorrelationIdHolder.runInScope` (Kafka listeners), and still in scope inside a `REQUIRES_NEW` method because propagation doesn't change threads. |
| `OrderStatusStreamListener.java` | New `@Component`. One `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` method that hands the event to the registry. This is the structural guarantee that a subscriber never sees an uncommitted or later-rolled-back status: Spring only invokes an `AFTER_COMMIT` listener once the publishing transaction has actually committed, and never invokes it at all if the transaction rolled back. |
| `OrderEventStreamRegistry.java` | New `@Component`. A `ConcurrentHashMap<SseEmitter, String>` of connected clients (value = the connection's `orderId` filter, or `""` for "every order" — chosen because `ConcurrentHashMap` cannot hold a `null` value, and `null` is exactly what an unfiltered `orderId` query parameter resolves to; this was the cause of one bug, see §3). `register()` creates and tracks an emitter (30-minute bounded timeout — `EventSource` reconnects automatically, so a periodic forced reconnect is harmless and prevents a half-open TCP connection pinning an emitter forever); `broadcast()` fans an event out to every emitter whose filter matches; a single-thread daemon `ScheduledExecutorService` sends an SSE comment (`:keep-alive`) to every connected emitter every 15 seconds. Failed sends (`IOException`/`IllegalStateException` — broken pipe or a concurrently-completed emitter) drop that emitter from the map. |
| `dto/OrderStatusChangedMessage.java` | New. The SSE message JSON body: `orderId`, `status`, `previousStatus`, `sourceEventId`, `correlationId`, `occurredAt`. |
| `OrderController.java` | New `GET /stream` handler (`produces = "text/event-stream"`), returning `SseEmitter`, declared ahead of `GET /{orderId}` for readability. Injects `OrderEventStreamRegistry` directly (thin passthrough, no business logic). |

### Design/judgment calls

- **Broadcast + client-side filter, not a per-order subscriber index.** The OpenAPI `orderId` query
  parameter is a per-connection filter, not a topic subscription, and this project's expected
  concurrent-viewer count is a handful of demo sessions. Broadcasting every transition to every
  emitter and filtering per-connection is simpler and no less correct than a per-order index — the
  registry's Javadoc documents this explicitly, as the brief asked.
- **Message shape.** Not frozen by the OpenAPI doc (deliberately — see its note that the schema
  lands once real transitions exist). Chose `OrderStatusChangedMessage` to mirror
  `OrderStatusHistoryEntryDto`'s already-frozen `status`/`sourceEventId`/`occurredAt` naming, plus the
  three fields the prose explicitly calls out: `orderId` (needed because one connection can carry
  every order's transitions), `previousStatus`, and `correlationId`.
- **`sourceEventId` is `null` on internal transitions.** Two of Order Service's five transactional
  methods write two history rows per inbound event (`INVENTORY_RESERVED` + `PAYMENT_PENDING`;
  `PAID` + `FULFILLMENT_PENDING` — docs/order-state-machine.md's internal transitions). The SSE
  stream reflects this honestly: the internal half of each pair carries `sourceEventId: null` and
  `previousStatus` pointing at the row that came immediately before it in the same transaction. This
  is asserted explicitly in `OrderStreamIntegrationTest`.
- **Route ordering.** `GET /stream` is declared textually ahead of `GET /{orderId}` in
  `OrderController` to document the OpenAPI note (`stream` is a reserved order id) at the point a
  future edit could accidentally break it, even though Spring resolves literal path segments ahead of
  template variables regardless of declaration order. `OrderStreamIntegrationTest.streamPathIsNotShadowedByOrderIdTemplate`
  proves the routing is actually correct, not just documented as intended.

### How events-only-after-commit was verified

Structurally: the publish call lives inside the same `REQUIRES_NEW` transactional method that writes
the `order_status_history` row, and delivery to SSE clients only happens from an
`AFTER_COMMIT`-phase `@TransactionalEventListener`. There is no path from "wrote the row" to "client
sees it" that doesn't pass through a real Postgres commit.

Behaviorally: `OrderStreamIntegrationTest.streamEmitsRealTransitionsInCommitOrder` opens a real HTTP
SSE connection with `java.net.http.HttpClient` (not a mock — a stub client can't distinguish a real
long-lived stream from a request/response pair), drives a real happy-path order through real Kafka
records (published with the same `EventPublisher` bean a real upstream service would use, per
`AbstractIntegrationTest`'s existing pattern), and asserts the six SSE messages arrive in exactly the
order the database's own `order_status_history` should show, with `previousStatus` chaining
correctly across the two internal-transition pairs and `sourceEventId` populated only where a real
inbound event caused the row.

### Automated tests — `OrderStreamIntegrationTest.java` (new, 3 tests)

1. `streamPathIsNotShadowedByOrderIdTemplate` — `GET /api/orders/stream` returns `200` with a
   `text/event-stream` content type, not a `404` from `getOrder("stream")`.
2. `streamEmitsRealTransitionsInCommitOrder` — the full happy-path sequence described above.
3. `orderIdFilterExcludesOtherOrdersTransitions` — a connection filtered to one order never receives
   another order's transitions.

Full order-service suite: **20 tests, 0 failures** (5 pre-existing test classes + the new one).

### Manual live verification

```
$ docker compose up -d postgres kafka   # + inventory/payment/fulfillment-service also running
$ curl -N localhost:8081/api/orders/stream &
$ curl -X POST localhost:8081/api/orders -H "Content-Type: application/json" \
    -d '{"customerId":"demo-customer","items":[{"sku":"SKU-001","quantity":1}]}'
{"id":"order-20001","status":"PENDING","createdAt":"2026-08-18T21:23:41.222902Z"}
```

Stream output (real time, all four services live):

```
event:order-status-changed
data:{"orderId":"order-20001","status":"PENDING","previousStatus":null,"sourceEventId":null,"correlationId":"eb7f7631-eba4-4bed-a536-e36754a47ad0","occurredAt":"2026-08-18T21:23:41.222902Z"}

event:order-status-changed
data:{"orderId":"order-20001","status":"INVENTORY_RESERVED","previousStatus":"PENDING","sourceEventId":"78d19499-634b-4011-a452-e751580a5c78","correlationId":"eb7f7631-eba4-4bed-a536-e36754a47ad0","occurredAt":"2026-08-18T21:23:42.275629Z"}

event:order-status-changed
data:{"orderId":"order-20001","status":"PAYMENT_PENDING","previousStatus":"INVENTORY_RESERVED","sourceEventId":null,"correlationId":"eb7f7631-eba4-4bed-a536-e36754a47ad0","occurredAt":"2026-08-18T21:23:42.280201Z"}

event:order-status-changed
data:{"orderId":"order-20001","status":"PAID","previousStatus":"PAYMENT_PENDING","sourceEventId":"6328e555-84f6-4d59-82ea-a555710747a5","correlationId":"eb7f7631-eba4-4bed-a536-e36754a47ad0","occurredAt":"2026-08-18T21:23:42.685810Z"}

event:order-status-changed
data:{"orderId":"order-20001","status":"FULFILLMENT_PENDING","previousStatus":"PAID","sourceEventId":null,"correlationId":"eb7f7631-eba4-4bed-a536-e36754a47ad0","occurredAt":"2026-08-18T21:23:42.687236Z"}

event:order-status-changed
data:{"orderId":"order-20001","status":"FULFILLED","previousStatus":"FULFILLMENT_PENDING","sourceEventId":"d3718d62-09a9-44c8-a71b-5c833cfe2edd","correlationId":"eb7f7631-eba4-4bed-a536-e36754a47ad0","occurredAt":"2026-08-18T21:23:43.250833Z"}

:keep-alive
```

`GET /api/orders/order-20001` afterward shows the identical `statusHistory` sequence (same
`sourceEventId`s, same timestamps) — the SSE stream and the database agree exactly, as it must given
the AFTER_COMMIT wiring.

---

## 2. Part 2 — Spring Boot Actuator health, all four services

### What was built

Identical change applied to all four services (`order-service`, `inventory-service`,
`payment-service`, `fulfillment-service`):

- `pom.xml`: added `spring-boot-starter-actuator`.
- `application.yml`: added
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health   # narrowly scoped to health only — metrics/info are Phase 9's job
    endpoint:
      health:
        probes:
          enabled: true      # adds the liveness/readiness endpoint groups
    health:
      livenessstate:
        enabled: true
      readinessstate:
        enabled: true
  ```

No custom health indicator was written. `spring-boot-starter-actuator` plus
`spring-boot-starter-data-jpa`/the Postgres driver already on the classpath gives every service
Spring Boot's default `DataSourceHealthIndicator` for free, which is what actually answers
`/actuator/health`'s DB-connectivity question.

### Kafka health — checked, not built

Spring Boot 4.1.0's Kafka Actuator auto-configuration (`KafkaHealthIndicator`) requires
`spring-kafka`'s admin client to be reachable and, per Spring Boot's own conditional wiring, is not
active by default the way `DataSourceHealthIndicator` is — none of the four services showed a
`kafka` entry under `/actuator/health`'s `components` after adding the starter (all four already
depend on `spring-boot-starter-kafka`). Per the brief ("don't hand-build one if it's not trivial"),
this was left as-is and is reported here rather than silently built around.

### Live verification (all four services, real Postgres/Kafka via `docker compose up -d`)

```
$ for p in 8081 8082 8083 8084; do curl -s http://localhost:$p/actuator/health; echo; done
{"groups":["liveness","readiness"],"status":"UP"}   # order-service
{"groups":["liveness","readiness"],"status":"UP"}   # inventory-service
{"groups":["liveness","readiness"],"status":"UP"}   # payment-service
{"groups":["liveness","readiness"],"status":"UP"}   # fulfillment-service
```

`/actuator/health/liveness` and `/actuator/health/readiness` both return `{"status":"UP"}` on all
four.

**DOWN behavior confirmed real**, not fabricated — stopped the real Postgres container:

```
$ docker stop orderfulfillment-postgres
$ curl -s -w "\nHTTP:%{http_code}\n" http://localhost:8084/actuator/health
{"groups":["liveness","readiness"],"status":"DOWN"}
HTTP:503
```

(Confirmed the same on inventory-service and payment-service.) Restarting Postgres brought all three
back to `UP` within a few seconds, with no service restart needed — exactly the "temporarily
unavailable dependency" behavior `high-level-design.md` §16 asks an agent to be able to explain, and
it's real HikariCP connection-validation failure surfacing through `DataSourceHealthIndicator`, not
a simulated flag.

---

## 3. A bug the tests and live curl both caught

`OrderEventStreamRegistry.register()` initially did `emitters.put(emitter, orderIdFilter)` directly.
`ConcurrentHashMap` throws `NullPointerException` on a `null` value, and an unfiltered
`GET /api/orders/stream` (no `orderId` query parameter) resolves `orderIdFilter` to `null` — so
every unfiltered connection 500'd immediately. Caught by the first live `curl` against a manually
started instance (not by `mvn test`, since Testcontainers hadn't been run yet at that point); fixed
by storing `""` as the "no filter" sentinel instead. `GlobalExceptionHandler`
(`services/common/`, out of scope for this workstream) swallows the exception without logging it,
so this required temporarily running the service with `-Dlogging.level.org.springframework=DEBUG`
to see the real stack trace via Spring's own request-mapping debug log — no change was made to
`services/common/` to debug this (confirmed via `git diff` after investigation: zero net change).

A second, unrelated bug surfaced only in the test suite: the routing test originally used
`HttpResponse.BodyHandlers.discarding()`, which blocks `HttpClient.send()` until the response body
completes — never, for a live SSE stream — hanging the test indefinitely. Fixed by switching to
`BodyHandlers.ofInputStream()` (which returns as soon as headers arrive) and closing the stream
immediately after asserting on the status/headers.

A third issue was in the test itself, not the implementation: the main happy-path stream test
originally published `ShipmentCreated` immediately after `PaymentAuthorized` without waiting for the
order to actually reach `FULFILLMENT_PENDING` first, racing two independently-consumed Kafka topics
against each other and occasionally observing `FULFILLED` arrive before `PAID`/`FULFILLMENT_PENDING`.
Fixed by awaiting `FULFILLMENT_PENDING` via the REST endpoint before publishing `ShipmentCreated`,
matching the existing pattern in `OrderServiceIntegrationTest`.

---

## 4. Full test suite results

| Service | Tests | Failures | Errors |
|---|---|---|---|
| order-service | 20 | 0 | 0 |
| inventory-service | 23 | 0 | 0 |
| payment-service | 11 | 0 | 0 |
| fulfillment-service | 8 | 0 | 0 |

All run with `JAVA_HOME` pointed at Temurin 21 (per this workstream's environment note — the machine
default JDK 26 breaks Mockito).

---

## 5. Files changed

```
services/order-service/pom.xml                                                        (+actuator dep)
services/order-service/src/main/resources/application.yml                             (+management config)
services/order-service/src/main/java/com/orderfulfillment/order/OrderController.java   (GET /stream)
services/order-service/src/main/java/com/orderfulfillment/order/OrderPersistence.java  (publish events)
services/order-service/src/main/java/com/orderfulfillment/order/OrderStatusChangedEvent.java     (new)
services/order-service/src/main/java/com/orderfulfillment/order/OrderStatusStreamListener.java   (new)
services/order-service/src/main/java/com/orderfulfillment/order/OrderEventStreamRegistry.java    (new)
services/order-service/src/main/java/com/orderfulfillment/order/dto/OrderStatusChangedMessage.java (new)
services/order-service/src/test/java/com/orderfulfillment/order/OrderStreamIntegrationTest.java  (new)

services/inventory-service/pom.xml                                                    (+actuator dep)
services/inventory-service/src/main/resources/application.yml                         (+management config)

services/payment-service/pom.xml                                                      (+actuator dep)
services/payment-service/src/main/resources/application.yml                           (+management config)

services/fulfillment-service/pom.xml                                                  (+actuator dep)
services/fulfillment-service/src/main/resources/application.yml                       (+management config)
```

No file under `docs/planning/`, `docs/openapi/`, `docs/order-state-machine.md`,
`docs/db-ownership.md`, `docs/scenarios.md`, `docs/adr/`, `docs/architecture-diagram.md`,
`docs/events/`, `docs/reliability-pattern.md`, `services/common/`, `services/scenario-service/`, or
`frontend/` was touched.

---

## Addendum — flaky SSE test root-caused and fixed post-integration

**Date:** 2026-08-19. Scope unchanged: `services/order-service/` only.

`OrderStreamIntegrationTest.streamEmitsRealTransitionsInCommitOrder` was intermittently flaky when
run as part of the full monorepo suite (`mvn test` from the repo root), while passing reliably when
run alone (`mvn -pl services/order-service test -Dtest=OrderStreamIntegrationTest`). Two different
symptoms were seen across repeated full-suite runs: an `await()` timeout stuck at `PAYMENT_PENDING`
instead of reaching `FULFILLMENT_PENDING`, and — more often — the six SSE messages arriving with the
right `status` values in the right order but `forThisOrder.get(3).previousStatus()` reading
`"PENDING"` instead of the expected `"PAYMENT_PENDING"`.

### Investigation

The first hypothesis was concurrency in `OrderEventStreamRegistry`: `SseEmitter.send()` is not safe
to call from multiple threads concurrently on the same emitter instance (Spring's own Javadoc says
so), and this registry has several real candidates for that — any of the per-topic Kafka listener
threads calling `broadcast()` for the same unfiltered connection, plus an independent keep-alive
scheduler thread ticking on its own 15-second timer regardless of what `broadcast()` is doing. That
hazard is real and was fixed (`synchronized (emitter)` now wraps every send in both `broadcast()` and
the keep-alive tick, with the reasoning recorded in the class Javadoc) — but adding it and re-running
the full suite reproduced the *exact same* `previousStatus` failure again, proving it was not the
(whole) cause.

Thread-name-and-payload diagnostic logging was then added temporarily to `OrderEventStreamRegistry`
and `OrderPersistence.writeStatus` to capture the real sequence of writes as they happened. One
full-suite run reproduced the bug and the logs showed the actual mechanism directly:

```
writeStatus thread=payment-events-0-C-1  order=order-20010 status=PAID               prev=PENDING
writeStatus thread=payment-events-0-C-1  order=order-20010 status=FULFILLMENT_PENDING prev=PAID
writeStatus thread=inventory-events-0-C-1 order=order-20010 status=INVENTORY_RESERVED prev=PENDING
writeStatus thread=inventory-events-0-C-1 order=order-20010 status=PAYMENT_PENDING    prev=INVENTORY_RESERVED
```

`PaymentAuthorized` was processed by the `payment-events` Kafka listener thread *before*
`InventoryReserved` was processed by the independent `inventory-events` listener thread — a genuine
cross-topic ordering race. `OrderPersistence.writeStatus` has no state-machine guard: it just reads
the order's current status as `previousStatus` and overwrites it, so whichever transition's listener
thread happens to run first "wins," regardless of which one logically comes first in
docs/order-state-machine.md.

### Root cause

The test itself, not the production code, created the race the production code then faithfully
recorded: `streamEmitsRealTransitionsInCommitOrder` published `InventoryReserved` and immediately
published `PaymentAuthorized` with no `await()` between them. In the real system this reordering
cannot happen — Payment Service only ever emits `PaymentAuthorized` after consuming the
`PaymentRequested` that Order Service itself publishes from inside `onInventoryReserved`, once
`InventoryReserved`'s transaction has committed, so there's a genuine causal chain enforcing the
order. The test, by publishing both upstream events directly to simulate that chain, discarded that
causal guarantee — `InventoryReserved` and `PaymentAuthorized` land on two different topics consumed
by two independent listener container threads, and nothing but program order in the test thread
suggested they'd be processed in that order. Under an isolated run, processing `InventoryReserved`
(one local DB round trip) reliably finishes before the `payments.events` consumer thread even picks
up its message, so the race was invisible. Under full-suite load (many more active listener
containers and threads competing for CPU, heavier GC), that timing margin shrank enough for the
`payment-events` thread to occasionally win.

This is the same category of bug this test class had already hit once, for the *next* pair in the
chain: the original phase-5 report's §3 describes fixing an identical unawaited race between
`PaymentAuthorized`/`FULFILLMENT_PENDING` and `ShipmentCreated`. That fix was applied to the later
pair in the chain but the same hazard existing one step earlier, between `InventoryReserved` and
`PaymentAuthorized`, was missed.

### Fix

1. **Test fix (the actual root cause):** `OrderStreamIntegrationTest.streamEmitsRealTransitionsInCommitOrder`
   now awaits the order reaching `PAYMENT_PENDING` via the REST endpoint before publishing
   `PaymentAuthorized`, exactly mirroring the existing await before publishing `ShipmentCreated`.
2. **Real (independent) hardening kept alongside it:** `OrderEventStreamRegistry.broadcast()` and
   `sendKeepAlive()` now synchronize on the emitter instance for every send, closing the genuine
   `SseEmitter` concurrent-write hazard described above. This did not turn out to be the trigger for
   this specific flake, but it is a real latent bug (multiple listener threads and the keep-alive
   thread really can write to the same unfiltered connection) worth fixing regardless, and is now
   documented in the class Javadoc.
3. **Test hygiene:** `streamEmitsRealTransitionsInCommitOrder` and
   `orderIdFilterExcludesOtherOrdersTransitions` now close the HTTP response stream in their `finally`
   blocks (not just `reader.interrupt()`, which cannot unblock a thread parked in a blocking socket
   read). Without this, each test's SSE connection — and its server-side `SseEmitter`, since the
   `@SpringBootTest` context and `OrderEventStreamRegistry` singleton are shared across every test
   class in the module — stayed registered for the rest of the suite run, needlessly growing the set
   of concurrently-live emitters every later test's broadcasts and keep-alive ticks had to coexist
   with.

### Verification

- Isolated: `mvn -pl services/order-service test -Dtest=OrderStreamIntegrationTest` — `Tests run: 3,
  Failures: 0, Errors: 0` (run both immediately after the fix, and again as a final check).
- Full monorepo suite, `mvn test` from the repo root, run three consecutive times after the fix — all
  three `BUILD SUCCESS`, order-service's 20 tests (including all 3 of
  `OrderStreamIntegrationTest`) green every time:
  - Run 1: `order-service ... SUCCESS [01:56 min]`, full reactor `BUILD SUCCESS`.
  - Run 2: `OrderStreamIntegrationTest` `Tests run: 3, Failures: 0, Errors: 0`, full reactor
    `BUILD SUCCESS`.
  - Run 3: `OrderStreamIntegrationTest` `Tests run: 3, Failures: 0, Errors: 0`, full reactor
    `BUILD SUCCESS`.

### Files changed in this addendum

```
services/order-service/src/main/java/com/orderfulfillment/order/OrderEventStreamRegistry.java  (per-emitter send synchronization)
services/order-service/src/test/java/com/orderfulfillment/order/OrderStreamIntegrationTest.java (await before publishing PaymentAuthorized; close response stream in finally)
```

`services/order-service/src/main/java/com/orderfulfillment/order/OrderPersistence.java` was
temporarily instrumented with diagnostic logging during investigation; that logging was removed
before landing the fix, so this file has no net change.
