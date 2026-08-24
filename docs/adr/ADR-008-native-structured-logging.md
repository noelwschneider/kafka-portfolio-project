# ADR-008: Native Spring Boot structured logging (ECS), not an encoder library

- **Status:** Accepted. Phase 9.
- **Date:** 2026-08-19

## Context

`docs/planning/engineering-rules.md` rule 17 requires "consistent logging with correlation IDs."
`docs/planning/sprint-1/high-level-design.md`'s Observability section asks every relevant log line to
carry service name, order ID, correlation ID, event ID, and event type where possible.

Correlation-id propagation itself already existed before this phase:
`CorrelationIdFilter` (an `OncePerRequestFilter`) puts the request's correlation id into SLF4J's
MDC (`MDC_KEY = "correlationId"`) for every HTTP request, and `CorrelationIdHolder.runInScope`
does the same for every `@KafkaListener` thread. What was missing was that no service's logging
configuration actually rendered the MDC value anywhere, and every `application.yml` had a bare
`logging.level.com.orderfulfillment: INFO` with no pattern or structured-format setting at all.

Spring Boot 4.1 (this project's pinned parent version) ships native structured logging support —
`logging.structured.format.console`/`.file`, with built-in `ecs`, `logstash`, and `gelf` formats —
requiring no extra dependency. `logstash-logback-encoder` (the more commonly reached-for library
before this feature existed) would add a dependency and a Logback XML config for the same outcome.

## Decision

Every backend service's `application.yml` sets:

```yaml
logging:
  structured:
    format:
      console: ecs
```

No extra dependency, no custom `logback-spring.xml`.

**ECS over `logstash`, the other built-in option that was actually tried first:** both formats
put MDC entries in the JSON output, but only `ecs` maps `spring.application.name` to a top-level
`service.name` field. A quick local test (`services/order-service` run standalone) confirmed
`logstash`'s output has no service-identifying field at all — every service's lines would look
identical except for their content, defeating half the "service name" requirement in
high-level-design.md's Observability section. `ecs` was verified locally to include
`service.name`, and MDC's `correlationId` shows up as a top-level `correlationId` field on every
line where it's set (verified via a live HTTP request carrying `X-Correlation-Id` and a captured
console line containing that exact value).

## A gap this phase also had to close, beyond the logging pattern

Wiring the pattern alone was not sufficient to satisfy the actual gate ("a scenario can be traced
across services via correlation ID without guessing"). Auditing every `log.*` call in the
codebase before this phase found only 32 call sites total, and on the happy path of the domain
Kafka consumers (`OrderInventoryEventsConsumer`, `OrderPaymentEventsConsumer`,
`OrderFulfillmentEventsConsumer`, `InventoryOrderEventsConsumer`, `InventoryPaymentEventsConsumer`,
`PaymentOrderEventsConsumer`, `FulfillmentPaymentEventsConsumer`) the only `INFO` log line in each
was the duplicate-delivery skip branch — an edge case, never hit on a normal run. A live
`standard-order` scenario run before this fix produced zero log output identifying the workflow in
4 of the 5 services. This phase adds one `INFO` line per consumer's happy path (right after the
event is confirmed relevant, before/around the side effect), plus `OrderController.createOrder`
(the workflow's first hop) and `ScenarioRunExecutor` (where the scenario's correlation id is
minted). See the phase report for the full list and the actual grepped proof.

A second, more serious gap surfaced during verification: `GlobalExceptionHandler.handleUnexpected`
(`services/common`) caught every uncaught exception and returned a 500 `ApiError` — carrying the
correlation id in the response body — but never logged the exception anywhere. A real 500 during
verification left zero trace in any service's log, in direct contradiction of this phase's whole
purpose. Fixed by adding `log.error(..., ex)` inside that handler; MDC's correlationId attaches
automatically through the same ECS structured-logging path. This was not a hypothetical: it is
what surfaced the actual bug hit during verification (a wrong URL in a manual test — see the phase
report), and without the fix that bug would have been undiagnosable from logs alone.

## Alternatives considered

**`logstash-logback-encoder` + custom `logback-spring.xml`.** More configurable (custom field
names, nested vs. flat MDC), and a common choice in real production systems already familiar to
the audience this portfolio targets. Rejected because it is an entire extra dependency and a
Logback XML file for a capability Spring Boot 4.1 now ships natively — `docs/planning/
engineering-rules.md` rule 11 asks for extra infrastructure to be justified, and "we're used to it
from other projects" isn't a justification when the built-in option covers the same ground.

**Plain-text console pattern with `%X{correlationId}` appended, no structured format at all.**
Simpler to read by eye in a terminal during a live demo. Rejected as the primary format because it
does not satisfy "structured logs" as a phase deliverable in its own right (implementation-phases.md
Phase 9: "Add: structured logs, correlation IDs..." — two separate items), and because
`docker compose logs | grep <correlation-id>` works identically well against JSON lines — the
grep does not care that the surrounding line is JSON.

**`gelf` format (the third built-in option).** Designed for direct shipping to Graylog over
UDP/TCP, not for console/stdout consumption the way this project reads logs (`docker compose
logs`). Not evaluated further — no Graylog in this stack and no plan to add one.

## Consequences and tradeoffs

**Accepted costs.**

- Console output is now one JSON object per line instead of a short human-formatted line — worse
  to eyeball scroll through during a live demo, though `docker compose logs -f <service> | grep
  <term>` or piping through `jq` handles it well, and that is the documented tracing workflow
  (see the phase report), not raw scrollback reading.
- ECS's nesting (`log.level`, `service.name`, `process.thread.name`) means a few Micrometer/Kafka
  library log fields land in slightly unusual places (e.g., `tags: ["COMMONS-LOGGING"]` on records
  from libraries that route through JCL) — cosmetic, does not affect correlation-id tracing.

**What it buys.**

- Zero new dependencies, no Logback XML to maintain per service, consistent with "mostly wiring
  existing libraries" (this workstream's assigned tier in `docs/planning/sprint-1/execution-plan.md`).
- Every log line, from every one of the 5 backend services, is one `grep <correlation-id>` away
  from being found — verified live, not assumed (phase report).
- The same JSON lines are immediately Prometheus/Grafana/ELK-shippable later without any format
  migration, if this project's optional observability stack grows further.
