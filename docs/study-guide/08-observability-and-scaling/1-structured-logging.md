# 8.1 — Structured logging

[← Chapter 8](README.md) · [Next: Metrics →](2-metrics.md)

Phase 9's gate:

> A scenario can be followed across services without guessing what happened.

Which turned out to require two things, only one of which was the logging configuration.

---

## What already existed, and what was missing

The [correlation-ID plumbing](../patterns/correlation-id-propagation.md) was built in
[Chapter 3](../03-kafka-and-services/3-correlation-ids.md). `CorrelationIdFilter` put the ID into
SLF4J's MDC for every HTTP request; `CorrelationIdHolder.runInScope` did the same for every Kafka
listener thread.

And it rendered nowhere:

> no service's logging configuration actually rendered the MDC value anywhere, and every
> `application.yml` had a bare `logging.level.com.orderfulfillment: INFO` with no pattern or
> structured-format setting at all.

**The MDC is a place to put values, not a mechanism for emitting them.** The plumbing was correct and
completely invisible — every line was written without the one field that made it traceable.

---

## Structured logging, and why it is not just formatting

A conventional log line is a sentence:

```
2026-08-19 14:03:22.118  INFO 1 --- [ntainer#0-0-C-1] c.o.i.InventoryOrderEventsConsumer : Processing OrderCreated 0c7c3acd for order order-21873
```

Readable, and opaque to anything that is not a human with a regular expression. Which service wrote
it? Parse the thread name, or infer it from the logger. Which correlation ID? It is not there at all.

A structured line is a record:

```json
{"@timestamp":"2026-08-19T14:03:22.118Z","log.level":"INFO","service.name":"inventory-service",
 "correlationId":"d89512f7-b544-4170-b66b-2e93f475ea8f",
 "message":"Processing OrderCreated 0c7c3acd for order order-21873"}
```

Same information, plus fields. **`grep` becomes a query.** Filter by `correlationId` and get one
workflow. Filter by `service.name` and `log.level` and get one service's errors. No pattern to
maintain, no ambiguity when a message happens to contain something that looks like an ID.

## The decision: native, not a library

```yaml
logging:
  structured:
    format:
      console: ecs
```

Four lines of configuration. No dependency, no `logback-spring.xml`.

Spring Boot 4.1 ships structured logging natively with `ecs`, `logstash`, and `gelf` built in. The
conventional choice — `logstash-logback-encoder` — was rejected on a rule rather than a preference:

> it is an entire extra dependency and a Logback XML file for a capability Spring Boot 4.1 now ships
> natively — `engineering-rules.md` rule 11 asks for extra infrastructure to be justified, and **"we're
> used to it from other projects" isn't a justification** when the built-in option covers the same
> ground.

### ECS over logstash, decided by testing both

Both put MDC entries in the output. The difference was found by trying them:

> only `ecs` maps `spring.application.name` to a top-level `service.name` field. A quick local test
> confirmed `logstash`'s output has **no service-identifying field at all** — every service's lines
> would look identical except for their content, defeating half the "service name" requirement.

**ECS** is Elastic Common Schema — a published field-naming convention (`@timestamp`, `log.level`,
`service.name`, `error.type`) that anything in the Elastic ecosystem understands without mapping.

Two things to take from this. **The decision was made by running both and looking at the output**,
not by reading documentation. And `spring.application.name` — set in
[Chapter 2](../02-domain/1-project-skeleton.md) as an apparently cosmetic line — is what makes the
whole thing work.

---

## The gap the configuration did not close

This is the part worth studying, because it is where a phase nearly declared victory on a
configuration change that satisfied nothing.

> Wiring the pattern alone was not sufficient to satisfy the actual gate. Auditing every `log.*` call
> in the codebase before this phase found only **32 call sites total**, and on the happy path of the
> domain Kafka consumers the only `INFO` log line in each was the **duplicate-delivery skip branch** —
> an edge case, never hit on a normal run. A live `standard-order` scenario run before this fix
> produced **zero log output identifying the workflow in 4 of the 5 services.**

Read that again. Correlation-ID propagation: working. Structured logging: configured. Every field
correct.

**And a normal scenario run produced no log lines at all in four of five services** — because the only
lines the consumers had were on branches a successful run never takes. A perfect tracing mechanism
attached to nothing.

The fix was not clever: one `INFO` line per consumer's happy path, *"right after the event is confirmed
relevant, before/around the side effect,"* plus `OrderController.createOrder` (the workflow's first
hop) and `ScenarioRunExecutor` (where a scenario's correlation ID is minted).

Which is why those two lines are positioned the way [Chapter 5](../05-scenarios-and-frontend/README.md)
noted — *inside* the correlation scope, as the first statement, so they are the first line of the
trace rather than an untagged line just outside it.

**The lesson generalizes past logging.** A mechanism can be perfectly correct and completely useless
because nothing invokes it. The audit — count the call sites, run the real workflow, check what
actually came out — is what caught it, and *"a live standard-order scenario run before this fix
produced zero log output in 4 of the 5 services"* is the kind of measurement worth taking before
declaring a gate met.

---

## The second gap: a 500 that logged nothing

> `GlobalExceptionHandler.handleUnexpected` caught every uncaught exception and returned a 500
> `ApiError` — carrying the correlation id in the response body — but **never logged the exception
> anywhere.** A real 500 during verification left zero trace in any service's log, in direct
> contradiction of this phase's whole purpose.

The worst possible failure mode: the client is told something broke, and the server has no record of
what.

And note how it was found — *"it is what surfaced the actual bug hit during verification (a wrong URL
in a manual test), and without the fix that bug would have been undiagnosable from logs alone."* The
gap was discovered by hitting it.

The fix is one line, and it is why [Chapter 2](../02-domain/3-the-http-layer.md) builds the handler
with it from the start:

```java
log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
```

MDC's `correlationId` attaches automatically through the ECS path, so the log line and the error
response the user is holding carry the same identifier.

> **We got this wrong.** Both gaps — consumers with no happy-path logging, and a catch-all that
> discarded its exception — existed from Phase 1 until Phase 9.
> [Chapter 10](../10-retrospective/README.md).

---

## What it looks like in use

```bash
docker compose logs | grep d89512f7-b544-4170-b66b-2e93f475ea8f
```

Every line, from all five services, for one workflow, in order. The `application.yml` comment states
the goal precisely:

> `docker compose logs <service> | grep <correlation-id>` finds a workflow's hops across all 5 services
> **without decoding a custom text pattern**, and every line is self-describing which service emitted
> it.

Where to get the correlation ID from, in practice:

- The `X-Correlation-Id` **response header** on any request you made.
- The `correlationId` field in an **`ApiError` body**, if something failed.
- The **scenario run**, which mints one per run and shows it in the UI.
- Any **event envelope** in the Event Explorer.

The same value in all four places, because it is the same value everywhere.

> **Where this pays off.** ECS output is JSON on stdout, which is exactly what a log aggregator wants.
> This project does not ship one — no Elasticsearch, no Loki, no cloud logging — and that is a scope
> decision, not an oversight. The format means adding one later is a collector configuration rather
> than an application change.

---

[← Chapter 8](README.md) · [Next: Metrics →](2-metrics.md)
