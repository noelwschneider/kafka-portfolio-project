# Phase 9 — Observability

**Scope:** structured logs with correlation ids visible on every line, Actuator metrics exposure,
and (stretch goal, reached) a Prometheus + Grafana stack with one real dashboard. Gate: "a scenario
can be traced across services via correlation ID without guessing"
(`docs/planning/execution-plan.md`'s Phase 9 entry).

---

## 1. What was already in place vs. what this phase built

Correlation-id propagation existed before this phase and was not rebuilt: `CorrelationIdFilter`
(`services/common`) puts the request's `X-Correlation-Id` (or a freshly minted one) into SLF4J's
MDC for every HTTP request; `CorrelationIdHolder.runInScope` does the same for every
`@KafkaListener` thread; the id rides on every Kafka `EventEnvelope` and every outbound HTTP call
between services. Confirmed both by reading the code and by exercising it live (§4).

What this phase actually built:

1. **Structured JSON logging (ECS format)** on every backend service's console output, via Spring
   Boot 4.1's native `logging.structured.format.console: ecs` — no new dependency. See
   `docs/adr/ADR-008-native-structured-logging.md` for the format choice (`ecs` over `logstash`)
   and the full reasoning.
2. **`INFO`-level log lines on the happy path** of every domain Kafka consumer and the order
   creation endpoint. This was not originally scoped as "add log statements" — the phase brief
   framed the gap as purely a missing log pattern — but auditing the codebase found only 32
   `log.*` call sites total, and the only `INFO` line in each of the 7 domain consumers was the
   duplicate-delivery skip branch, an edge case never hit on a normal run. Without this, wiring
   the pattern alone would have produced a JSON format with correlation ids attached to nothing,
   on a happy-path run. See §3 for the full list of files touched.
3. **Fixed a real bug found during verification**: `GlobalExceptionHandler.handleUnexpected`
   (`services/common`) caught every uncaught exception, put the correlation id in the response
   body, and then discarded the exception — logging nothing. A live 500 during verification (§4)
   left zero trace in any service's log until this was fixed. This is squarely a Phase 9 defect
   (an error is exactly the case where tracing matters most) so it's fixed here rather than
   flagged for a later phase.
4. **Actuator exposure** widened from `health` only to `health,metrics,prometheus` on all 5
   backend services.
5. **Prometheus + Grafana** (stretch goal, both docs mark optional) — reached, see §6.

## 2. Files touched

**Logging configuration** — every backend service's `src/main/resources/application.yml`
(`order-service`, `inventory-service`, `payment-service`, `fulfillment-service`,
`scenario-service`): added `logging.structured.format.console: ecs`, widened
`management.endpoints.web.exposure.include` to `health,metrics,prometheus`.

**Happy-path log lines added:**

| File | What was added |
|---|---|
| `services/order-service/.../OrderController.java` | `log.info("Order {} created", ...)` after `orderService.createOrder(...)` — the workflow's first hop |
| `services/order-service/.../OrderInventoryEventsConsumer.java` | `INFO` line in both `onInventoryReserved` and `onInventoryReservationFailed`, before the persistence call |
| `services/order-service/.../OrderPaymentEventsConsumer.java` | Same, in `onPaymentAuthorized`/`onPaymentRejected` |
| `services/order-service/.../OrderFulfillmentEventsConsumer.java` | Same, before `appendStatus(..., FULFILLED, ...)` |
| `services/order-service/.../OutboxDispatcher.java` | `INFO` line per successfully published outbox row, logged explicitly with the row's own `correlationId` parsed from its stored envelope — not via MDC, because one dispatch tick can batch rows from several unrelated workflows and there is no single correlation id to bind to the whole loop |
| `services/inventory-service/.../InventoryOrderEventsConsumer.java` | `INFO` line right after decoding `OrderCreatedPayload` |
| `services/inventory-service/.../InventoryPaymentEventsConsumer.java` | Same, after decoding `PaymentRejectedPayload` |
| `services/payment-service/.../PaymentOrderEventsConsumer.java` | Same, after decoding `PaymentRequestedPayload` |
| `services/fulfillment-service/.../FulfillmentPaymentEventsConsumer.java` | Same, after decoding `PaymentAuthorizedPayload` |
| `services/scenario-service/.../ScenarioRunExecutor.java` | `log.info("Starting scenario run {} ({})", ...)`, moved inside the `CorrelationIdHolder.runInScope` lambda (previously only `runner.run(ctx)` was in scope) so the very first line of a scenario's trace is captured |
| `services/common/.../GlobalExceptionHandler.java` | `log.error(..., ex)` in `handleUnexpected` — see §1 point 3 |

**Actuator/metrics:** `micrometer-registry-prometheus` added to all 5 backend service `pom.xml`
files (was absent from every one before this phase).

**Prometheus/Grafana (stretch):** `docker-compose.yml` (two new services, `prometheus` and
`grafana`); `infrastructure/observability/prometheus.yml`; `infrastructure/observability/grafana/
provisioning/datasources/prometheus.yml`; `infrastructure/observability/grafana/provisioning/
dashboards/dashboards.yml`; `infrastructure/observability/grafana/dashboards/
order-fulfillment-overview.json`.

**Docs:** `docs/adr/ADR-008-native-structured-logging.md` (new), root `README.md` (Grafana
URL/credentials, `/actuator/metrics`+`/actuator/prometheus` paths, a "tracing a scenario" section
with the actual `grep` command).

## 3. Verification method

Ran the entire stack via `docker compose up -d --build` (Postgres, Kafka, all 5 backend services,
frontend, Prometheus, Grafana — 9 containers, all reported healthy). No infra was already running
at the start of this session, so this was started fresh and torn down at the end (§7).

## 4. Cross-service correlation-id trace — the actual gate proof

Triggered a real scenario run:

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order
{"id":"run-105","scenarioName":"standard-order","status":"RUNNING",
 "correlationId":"7b0aff2c-ba2e-4d4c-98b2-8c87679ebe45", ...}
```

Polled it to completion (`GET /demo/scenario-runs/run-105`) — real terminal status `FULFILLED`,
real `orderId: order-20008`, a 7-step timeline (`PUT /demo/payment-behavior` → `POST /api/orders`
→ `INVENTORY_RESERVED` → `PAYMENT_PENDING` → `PAID` → `FULFILLMENT_PENDING` → `FULFILLED`) over
~5.6 seconds — a real request/event/persistence flow, not a frontend animation.

Then, for each of the 5 backend services:

```bash
docker logs orderfulfillment-<service> | grep 7b0aff2c-ba2e-4d4c-98b2-8c87679ebe45
```

Real, non-fabricated matches, one INFO line per hop (Kafka client noise filtered out here for
readability; the correlation id is present on those lines too, since MDC was still bound when the
producer lazily initialized on first send):

```
scenario-service : Starting scenario run run-105 (standard-order)
order-service    : Order order-20008 created
order-service    : Published OrderCreated for order order-20008 (correlationId=7b0aff2c-...)
inventory-service: Processing OrderCreated 151a2e9d-... for order order-20008
order-service    : Processing InventoryReserved b4cba92b-... for order order-20008
order-service    : Published PaymentRequested for order order-20008 (correlationId=7b0aff2c-...)
payment-service  : Processing PaymentRequested 14fc80f9-... for order order-20008
order-service    : Processing PaymentAuthorized b6a7b2dc-... for order order-20008
fulfillment-service: Processing PaymentAuthorized b6a7b2dc-... for order order-20008
order-service    : Processing ShipmentCreated 1a91b83a-... for order order-20008
```

Every one of the 5 services logged at least one line carrying `7b0aff2c-4d4c-...` (the
`correlationId` field of the ECS JSON, confirmed by parsing each matched line as JSON and printing
the field, not just the substring match). This is the actual literal proof of the phase gate — a
human (or `grep`) can trace this workflow across all 5 services without guessing.

**A genuine bug found and fixed via this same mechanism, before the successful run above:** the
first attempt used the wrong URL (`POST /demo/scenarios/standard-order/run` instead of the real
`POST /demo/scenarios/{scenarioName}`) and got a 500. Before the `GlobalExceptionHandler` fix
(§1 point 3), that 500 left literally zero log trace anywhere — its own correlation id
(`e2031104-...`) matched nothing in any service's logs. After the fix, the same class of error
produced:

```json
{"level":"ERROR","logger":"com.orderfulfillment.common.GlobalExceptionHandler",
 "message":"Unexpected error handling POST /demo/scenarios/standard-order/run",
 "correlationId":"49ccec21-8e36-4e92-b0b3-ab7dc51efe7a",
 "error":{"type":"org.springframework.web.servlet.resource.NoResourceFoundException", ...}}
```

— which immediately identified the real cause (wrong URL, `NoResourceFoundException`) and let the
retry with the correct URL succeed. This is exactly the "trace without guessing" property the
phase gate asks for, demonstrated on a real failure, not a staged one.

## 5. Metrics verification

```
$ curl -s http://localhost:8081/actuator/metrics | jq '.names | length'
263
$ curl -s http://localhost:8083/actuator/metrics | jq '.names | length'
260
$ curl -s http://localhost:8081/actuator/prometheus | head -5
# HELP application_ready_time_seconds Time taken for the application to be ready to service requests
# TYPE application_ready_time_seconds gauge
application_ready_time_seconds{main_application_class="com.orderfulfillment.order.OrderServiceApplication"} 30.424
...
```

Real, non-empty data on both `order-service` and `payment-service` (spot-checked as the two most
structurally different — outbox-owning vs. not).

## 6. Prometheus/Grafana stretch goal — reached

Both frozen docs mark this explicitly optional
(`docs/planning/high-level-design.md`'s Observability section: "Stronger later version — add
Micrometer, Prometheus, Grafana"; `docs/planning/implementation-phases.md` Phase 9: "Optional:
Prometheus, Grafana"). Pursued because required-adjacent work (steps 1–2 in the phase brief's
priority order) landed cleanly and there was comfortable budget left.

**Prometheus** (`prom/prometheus:v3.0.1`, added to `docker-compose.yml`) scrapes all 5 backend
services' `/actuator/prometheus` every 5s. Confirmed actually scraping, not just configured:

```
$ curl -s http://localhost:9090/api/v1/targets | jq -r '.data.activeTargets[] | "\(.labels.job) \(.health)"'
fulfillment-service up
inventory-service up
order-service up
payment-service up
scenario-service up
```

**Grafana** (`grafana/grafana:11.4.0`) is provisioned (not click-configured) with the Prometheus
datasource and one dashboard, `order-fulfillment-overview.json` — 5 panels: service up/down,
HTTP request rate by service, HTTP average latency by service, Kafka consumer records-consumed
rate by service, JVM heap used by service. Confirmed rendering real data, not an empty panel, by
querying the exact PromQL the request-rate panel uses directly against Prometheus's API after
running scenarios:

```
$ curl -s "http://localhost:9090/api/v1/query?query=sum+by+(job)+(rate(http_server_requests_seconds_count[1m]))"
{"status":"success","data":{"result":[
  {"metric":{"job":"scenario-service"},"value":[...,"0.3998..."]},
  {"metric":{"job":"fulfillment-service"},"value":[...,"0.3998..."]},
  {"metric":{"job":"inventory-service"},"value":[...,"0.3998..."]},
  {"metric":{"job":"payment-service"},"value":[...,"0.3998..."]},
  {"metric":{"job":"order-service"},"value":[...,"0.4180..."]}
]}}
```

Non-zero for all 5 services. Grafana's own dashboard search API also confirms the dashboard is
provisioned and reachable: `GET /api/search` returns the `orderfulfillment-overview` UID.
Anonymous viewer access is enabled (`GF_AUTH_ANONYMOUS_ENABLED`) so the dashboard is visible at
**http://localhost:3000** without logging in; `admin`/`admin` (dev-only, same posture as this
project's other local-only credentials) is only needed to edit it.

**Deliberately not done, and why:** Kubernetes manifests for Prometheus/Grafana. Phase 8's `kind`
path does not get this stretch addition — standing up a second in-cluster monitoring pair (with
its own PVCs, ConfigMaps, and a Grafana provisioning ConfigMap mount) is a materially bigger lift
than the Compose addition and was judged out of scope for staying budget-conscious this phase, per
the brief's explicit instruction not to let the stretch goal balloon the work. The Compose path is
the one this project's local-dev and demo workflow actually uses.

## 7. Test suite and cleanup

`mvn -q -pl services/order-service,services/inventory-service,services/payment-service,
services/fulfillment-service,services/scenario-service test` — exit code 0, all tests passed
across all 5 touched services (ran in the foreground, to completion, per this project's standing
"don't background a long-running verification step" convention).

`mvn -q -DskipTests install` (whole reactor) — clean build after every change, confirming
`OutboxDispatcher`'s signature change (`wireForm(String)` → `wireForm(JsonNode)`, needed to log a
row's `correlationId` without re-parsing) didn't break anything downstream.

No infra was already running when this session started (`docker compose ps` was empty at the
outset). Everything started for this phase — `postgres`, `kafka`, and later the full 9-container
stack — was torn down with `docker compose down` (no `-v`, preserving the named Postgres volume)
before finishing.

## 8. Judgment calls

- **ECS over `logstash`** for the structured console format — `logstash` doesn't add a
  service-identifying field, `ecs` does (`service.name` from `spring.application.name`). Full
  reasoning in `docs/adr/ADR-008-native-structured-logging.md`.
- **Added happy-path `INFO` logs beyond what the phase brief described as the gap.** The brief
  characterized the missing piece as purely "no log pattern prints the MDC value" — true, but
  insufficient on its own, since most of the codebase's few log statements only fire on the
  duplicate-delivery edge case. Treated this as in-scope for the phase's actual required
  deliverable (the gate literally requires tracing a real scenario run) rather than a separate
  follow-up.
- **Fixed `GlobalExceptionHandler`'s silent exception-swallowing** rather than filing it as a
  follow-up. It's a direct violation of this phase's purpose (an untraceable error is the worst
  case for "trace without guessing") and a one-line, low-risk fix, found through actually running
  the verification the brief asked for rather than assuming success.
- **`OutboxDispatcher` logs its row's correlation id explicitly rather than via MDC.** Its dispatch
  loop can batch rows from several unrelated workflows in one transaction, so there's no single
  correlation id to bind to the whole tick — MDC would either be wrong for some rows or require
  set/clear per row, which is more invasive than reading the value already being parsed out of the
  row's own stored envelope for the log line.
- **Reached the Prometheus/Grafana stretch goal**, including a real (not placeholder) dashboard,
  because required work left comfortable room; stopped short of extending it to the Kubernetes
  path for the reason in §6.
