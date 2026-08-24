# 5.1 — The scenario service

[← Chapter 5](README.md) · [Next: Server-Sent Events →](2-server-sent-events.md)

The fifth service, which owns no business data and exists entirely because of one Phase 0 decision.

---

## Why it exists

[ADR-002](../01-design-contract/3-state-and-api-contracts.md) forbade the cheap way to make a payment
fail:

```
POST /api/orders {"forcePaymentFailure": true}
```

Once the business API cannot carry scenario parameters, the scenario logic has to live *somewhere*.
That somewhere is Scenario Service, and it is a **control plane** — it orchestrates the system through
the same public APIs any client uses, and configures failure conditions through each service's own
`/demo` endpoints.

Two rules govern everything it does:

> **Scenario behavior is real.** Each scenario drives genuine HTTP requests, genuine Kafka records, and
> genuine persistence.
>
> **Scenarios use the normal APIs.** A scenario creates orders through `POST /api/orders`, the same
> endpoint any client uses.

It is also the one place the architecture permits synchronous service-to-service HTTP, and ADR-002 is
careful about why that is not a contradiction:

> Justified as control plane rather than workflow: no order transition depends on those calls, and a
> scenario has to be able to report a deterministic start.

**No order transition depends on those calls.** That is the test. The workflow is still entirely
event-driven; what is synchronous is the arrangement of the environment beforehand.

---

## The shape of a run

A run has three phases and they happen on two different threads.

```
POST /demo/scenarios/{name}
  → validate, mint runId + correlationId, persist the run as RUNNING, return  ← HTTP thread ends here
  → @Async: the runner executes                                               ← background thread
  → complete: mark COMPLETED or FAILED, publish the final status
```

The response returns as soon as the run exists, carrying a `runId` — the same asynchronous shape as
`POST /api/orders`, for the same reason. A scenario takes seconds; an HTTP request should not.

### The executor is its own bean

```java
/**
 * The actual background execution of one scenario run, on its own bean (so {@code @Async} goes
 * through a real Spring AOP proxy — self-invocation from {@link ScenarioExecutionService} would
 * silently run synchronously instead).
 */
@Component
public class ScenarioRunExecutor {

    @Async("scenarioExecutor")
    public void executeAsync(ScenarioRunner runner, String runId, String scenarioName, UUID correlationId) {
```

The **same proxy trap** as `@Transactional` from [Chapter 4](../04-reliability/README.md), with a
nastier failure mode. A self-invoked `@Transactional` method runs without a transaction; a
self-invoked `@Async` method runs **synchronously** — the HTTP request blocks for the whole scenario,
and nothing errors. It just gets slow, in a way that looks like a performance problem rather than a
missing annotation.

Any time you see a one-method class in a Spring codebase whose only apparent purpose is to be called
from elsewhere, this is usually why.

### Correlation scope is established here

```java
CorrelationIdHolder.runInScope(correlationId, () -> {
    // Phase 9: this is where a scenario's correlationId is minted — logged here, inside
    // the scope, so it's the first line of the trace a human would grep for across all
    // 5 services' logs.
    log.info("Starting scenario run {} ({})", runId, scenarioName);
    runner.run(ctx);
});
```

A third entry point for the [correlation-ID pattern](../patterns/correlation-id-propagation.md),
alongside the HTTP filter and the Kafka listeners. Scenario Service **mints** the ID for a run; every
order it creates carries it in an `X-Correlation-Id` header, and from there it propagates through
every event the workflow produces.

So one `correlationId` spans an entire scenario run — every HTTP call, every order, every event,
across five services. That is what makes a run's timeline assemblable at all.

Note also *where* the log line is: inside the scope, as the first statement, so it is the first line
of the trace rather than an untagged line just outside it.

---

## The runner abstraction

```java
public interface ScenarioRunner {
    String scenarioName();
    void run(ScenarioRunContext ctx);
}
```

Eight implementations, one per scenario, discovered by Spring and looked up by name. Adding a ninth
scenario means adding one `@Component` and a catalog entry.

`AbstractScenarioRunner` supplies the shared plumbing — and its Javadoc is honest about how thin it
is:

> Shared plumbing every `ScenarioRunner` needs — thin wrappers around HTTP-with-timeline-recording.

```java
protected OrderServiceClient.OrderCreationResult createOrder(
        String runId, String sku, int quantity, String customerId) {
    OrderServiceClient.OrderCreationResult result = orderServiceClient.createOrder(customerId, items);
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("statusCode", result.statusCode());
    if (result.orderId() != null) {
        detail.put("orderId", result.orderId());
    }
    timelineRecorder.append(runId, TimelineKind.HTTP, "POST /api/orders", detail);
    return result;
}
```

Two things about this small method.

**It records the real status code**, not an assumed one — *"the run timeline shows the HTTP 201
returning before the downstream events."* That ordering is the point of the timeline: it makes the
asynchrony visible. The 201 lands first, and the events that actually fulfil the order arrive
afterwards.

**`orderId` is added conditionally.** If the creation failed there is no order ID, and the field is
**absent** rather than null or empty. That is the timeline schema's own rule, and it recurs throughout
this service: *do not fabricate these fields.*

### The toolkit

```java
protected AbstractScenarioRunner(ScenarioToolkit toolkit) {
    this.orderServiceClient = toolkit.orderServiceClient();
    this.consumerControlClient = toolkit.consumerControlClient();
    // …six more
}
```

`ScenarioToolkit` is a **parameter object**: one injected dependency carrying eight collaborators, so
every runner has a one-argument constructor and adding a ninth collaborator does not touch eight
subclasses.

Worth noting as a deliberate exception to the rule from the
[DI primer](../technology/spring/dependency-injection.md) that a long constructor is useful pressure
against a class doing too much. That pressure is valuable when the constructor belongs to *one* class;
here it would just be eight identical edits. The exception is defensible precisely because the reason
for the original rule does not apply.

---

## Waiting for real outcomes

A scenario has to know when it is done, and there is exactly one dishonest way to do that: sleep for a
plausible duration and declare success.

Two components exist so it does not:

**`OrderStatusWatcher.awaitTerminal(runId, orderId)`** polls Order Service's real API until the order
reaches a terminal state, recording each observed transition as a timeline entry.

**`ConsumerLagService`** reads real consumer-group lag from the broker's admin API:

> Real consumer-group lag, read straight from the broker via the admin API — the same computation
> `kafka-consumer-groups.sh --describe` performs [...] so a scenario run can report a real, observed
> backlog instead of a guess, the same way `OrderStatusWatcher` reports real order-status transitions
> instead of a scripted wait.

And a detail in `totalLag` worth copying:

> Returns `0` if the group has no committed offsets yet [...] or if the broker call fails — this is a
> measurement aid, not a correctness gate, so a transient admin-API hiccup should not fail the
> scenario run.

**Classify each dependency as observation or correctness**, and let observation fail soft. A metrics
call that can fail a run is a metrics call that will eventually fail a run.

The same instinct appears in `DuplicateEventScenario`, which polls for its own event to appear in the
projection before republishing it — and *throws* rather than proceeding on a guess:

```java
throw new IllegalStateException(
        "OrderCreated for " + orderId + " was not observed by the event projection in time");
```

Here the dependency *is* correctness — you cannot republish a record you have not read — so it fails
hard, with a message that says exactly what did not happen.

---

## Its own persistence

Three tables in `scenario_service`: `scenario_runs`, `scenario_run_timeline`, and `events` (the
projection, [section 4](4-observing-the-system.md)).

Note what is **not** there: no orders, no reservations, no payments, no shipments. Scenario Service
observes and orchestrates; it owns no business data, and per ADR-004 it cannot read anyone else's
schema. Everything it knows, it learned through a public API or off a Kafka topic — which is a real
constraint with a real consequence, explored in [section 4](4-observing-the-system.md).

---

[← Chapter 5](README.md) · [Next: Server-Sent Events →](2-server-sent-events.md)
