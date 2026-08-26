# Issue #41 fix — independent verification

Verifying the implementer's report at
`docs/agent-reports/sprint-7/issue-41-retry-classification.md` against the actual artifact: the diff,
the rebuilt `docker compose` stack, and the two test suites.

## What changed

No source files changed. This is a verification pass only. The only file created is this report.

## How this was verified

### Criterion 1 — diff matches the report's description, `ConsumerErrorHandlerFactory.java` untouched

Confirmed via `git diff`/`git status`. The five real files changed exactly as claimed, and
`ConsumerErrorHandlerFactory.java` has zero diff:

```
$ git status --short
 M docs/CHANGELOG-contracts.md
 M docs/events/event-catalog.md
 M docs/reliability-pattern.md
 M services/common/.../kafka/KafkaTopicConfig.java
 M services/common/.../kafka/KafkaTopics.java
?? services/scenario-service/.../projection/ScenarioKafkaReliabilityConfig.java
(plus unrelated pre-existing changes to .claude/agents/*, docs/planning/README.md,
docs/workflow/agent-workflow.md, docs/planning/project-overview.md, .gitignore, deleted
docs/study-guide/* and docs/external/claude-effort.md — none related to issue #41, not touched by
this verification)

$ git diff -- services/common/src/main/java/com/orderfulfillment/common/kafka/ConsumerErrorHandlerFactory.java
(no output — file is byte-identical to HEAD)
```

`KafkaTopics.java` gained exactly `SCENARIO_DLQ = "scenario.dlq"` with a javadoc explaining the "one
DLQ per failing consumer" rule; `KafkaTopicConfig.java` gained exactly one `scenarioDlqTopic()`
`NewTopic` `@Bean` (3 partitions, replication 1, matching the four existing DLQ beans);
`event-catalog.md` §2 gained exactly one row for `scenario.dlq`; `CHANGELOG-contracts.md` gained one
dated entry; `reliability-pattern.md` §4.1 gained one paragraph. All read exactly as the report
describes — verified by reading the diffs directly, not by trusting the report's prose. **Pass.**

### Criterion 2 — root-cause claim (unwired listener)

Read `ScenarioKafkaReliabilityConfig.java` and `OrderKafkaReliabilityConfig.java` side by side — the
pattern is identical (`@Configuration` class, one `@Bean public DefaultErrorHandler
kafkaErrorHandler(ConsumerErrorHandlerFactory factory)` calling `factory.create(<DLQ topic>)`).
Confirmed all five `*KafkaReliabilityConfig` classes exist, one per service, with the new one being
genuinely new (not present before this session — matches `git status` showing it as untracked `??`):

```
$ find services -name "*KafkaReliabilityConfig.java"
services/payment-service/.../PaymentKafkaReliabilityConfig.java
services/order-service/.../OrderKafkaReliabilityConfig.java
services/inventory-service/.../InventoryKafkaReliabilityConfig.java
services/fulfillment-service/.../FulfillmentKafkaReliabilityConfig.java
services/scenario-service/.../projection/ScenarioKafkaReliabilityConfig.java
```

Also confirmed `EventProjectionConsumer.java` (the only `@KafkaListener` holder in Scenario Service,
`scenario-service-projection` consumer group, two listeners `onDomainRecord`/`onDlqRecord`) has no
`*ContainerFactory` override or other error-handler wiring of its own — it relies entirely on Spring
Boot's single auto-configured `CommonErrorHandler`, which before this fix had no bean to pick up.
**Pass.**

### Criterion 3 — test suites actually pass

Ran both suites fresh myself, not from the report's pasted output:

```
$ rm -f services/scenario-service/target/surefire-reports/*.txt
$ mvn -pl services/scenario-service -am test \
    -Dtest='!HighVolumeScenarioIntegrationTest,!InventoryContentionScenarioIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -q
$ echo EXIT=$?
EXIT=0
$ for f in target/surefire-reports/*.txt; do grep "Tests run" "$f"; done
Tests run: 1, Failures: 0, Errors: 0 -- ConsumerOutageScenarioIntegrationTest
Tests run: 2, Failures: 0, Errors: 0 -- DemoResetIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- DuplicateEventScenarioIntegrationTest
Tests run: 2, Failures: 0, Errors: 0 -- EventProjectionIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- IdleResetSchedulerIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- OutOfStockScenarioIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- PaymentFailureScenarioIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- PoisonMessageScenarioIntegrationTest
Tests run: 2, Failures: 0, Errors: 0 -- ScenarioConflictIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- StandardOrderScenarioIntegrationTest

$ mvn -pl services/common -am test -q
$ echo EXIT=$?
EXIT=0
```

All 10 scenario-service integration test classes and the common module's test suite pass, exit code
0 both times. The two excluded classes (`HighVolumeScenarioIntegrationTest`,
`InventoryContentionScenarioIntegrationTest`) match the same pre-existing, unrelated
memory-contention exclusions sprint-6's verification used — not something introduced by this fix.
**Pass.**

### Criterion 4 — live behavior against the running stack

Confirmed the new topic exists with the claimed shape, independently:

```
$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --describe --topic scenario.dlq
Topic: scenario.dlq	TopicId: jzg3yXLNR2KPdt2jNzcjTw	PartitionCount: 3	ReplicationFactor: 1
```

Read the actual DLQ record the implementer's own live reproduction produced (not just their pasted
excerpt — I consumed it myself from the topic):

```
$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 \
    --topic scenario.dlq --from-beginning --property print.headers=true --max-messages 5 --timeout-ms 8000
...
kafka_dlt-original-topic:inventory.events, ... kafka_dlt-original-consumer-group:scenario-service-projection,
x-delivery-attempts:1,x-failure-retryable:false,x-dead-lettered-at:2026-08-26T20:36:59.157494673Z,
x-failure-class:org.postgresql.util.PSQLException,x-failure-message:ERROR: duplicate key value
violates unique constraint "scenario_run_timeline_run_id_sequence_key" Detail: Key
(run_id, sequence)=(run-271, 2) already exists.
```

This is a genuine, persisted DLQ record with `x-delivery-attempts:1` and `x-failure-retryable:false`
— hard evidence the recoverer fired exactly once and dead-lettered correctly, independent of the
report's prose.

I then attempted my own **independent** fresh reproduction (without touching source, since I can't
edit files): ran `standard-order` twice more (`run-274`, `run-275`) via
`POST /demo/scenarios/standard-order`, and within ~1–3s of each completing (well inside the 10s
`late-event-grace-ms` window), published a synthetic duplicate `OrderCreated` (new eventId, same
`correlationId`/`orderId`) directly to `orders.events` via `kafka-console-producer`. Neither attempt
produced a collision or a DLQ record. Reading `TimelineRecorder.append()` and `ScenarioRunExecutor`
explains why: `forget(runId)` (which resets the in-memory sequence counter) and
`retireCorrelation(correlationId)` are scheduled and executed back-to-back on the same thread — there
is no window in the *shipped* code where the sequence counter has been reset but the correlation is
still registered, so a genuine duplicate-key collision essentially cannot happen without the
counterfactual edit (`forget()` decoupled from `retireCorrelation()`) the implementer's report
describes making and reverting. My two clean attempts not reproducing a collision is expected and is
itself consistent with the report's account — it is not evidence against the fix.

Since I cannot edit source to reinstate that counterfactual myself (verifier scope), and
`/actuator/beans` is not exposed on this service (`404 Not Found`), I rely on the persisted DLQ
record above — which required `ScenarioKafkaReliabilityConfig`'s bean to be picked up by Spring Boot's
Kafka auto-configuration at runtime for that exact `DeadLetterPublishingRecoverer` to exist and fire —
as the runtime-wiring proof requested by the fallback in criterion 4. Also confirmed the normal path
still projects correctly via my own two fresh `standard-order` runs (`run-274`, `run-275`), both
completing normally with `OrderCreated`/`InventoryReservationFailed` timeline entries populated. **Pass**, with the caveat noted above about not independently re-triggering the exact before/after counterfactual (see Deliberately not covered).

### Criterion 5 — environment left as claimed

```
$ docker compose ps -a --format "{{.Name}}: {{.Status}}"
orderfulfillment-frontend: Up 3 hours
orderfulfillment-fulfillment-service: Up 3 hours (healthy)
orderfulfillment-grafana: Up 3 hours
orderfulfillment-inventory-service: Up 3 hours (healthy)
orderfulfillment-kafka: Up 3 hours (healthy)
orderfulfillment-order-service: Up 3 hours (healthy)
orderfulfillment-payment-service: Up 3 hours (healthy)
orderfulfillment-postgres: Up 3 hours (healthy)
orderfulfillment-prometheus: Up 3 hours
orderfulfillment-scenario-service: Up 11 minutes (healthy)
```

Only `scenario-service` shows a shorter uptime (from the implementer's rebuild); all four domain
services, Kafka, Postgres, frontend, Grafana, and Prometheus are untouched at their original ~3h
uptime, matching the "did not rebuild order/inventory/payment/fulfillment" claim.

```
$ git status --short | grep -E "DeliveryAttemptTracker|ScenarioRunExecutor|docker-compose.yml"
(no output)
```

No residual diagnostic/temporary code in `DeliveryAttemptTracker.java`, `ScenarioRunExecutor.java`, or
`docker-compose.yml` — confirmed by direct `git diff`, not by trusting the report's own `git
status`/`git diff` excerpt. **Pass.**

I stood up no new infrastructure and produced only additive test data (three extra `standard-order`
scenario runs, `run-273`–`run-275`, and one synthetic duplicate `OrderCreated` record on
`orders.events` for each of the last two, both of which were silently ignored by the fixed consumer as
expected) against the already-running stack. Left the stack running, untouched otherwise, per the
environment rule.

## Judgment calls

- **Did not attempt to reinstate the implementer's counterfactual (`forget()` decoupled from
  `retireCorrelation()`) to force a fresh before/after collision myself**, since doing so requires
  editing `ScenarioRunExecutor.java`, which is out of scope for a verifier. Instead I did two things:
  (a) read the actual persisted DLQ record from the implementer's own prior live reproduction directly
  off the Kafka topic (not from their pasted report text) as hard evidence the wiring fires correctly,
  and (b) ran my own fresh reproduction attempts against the unmodified shipped code, which correctly
  did *not* collide — consistent with, and explained by, the code's actual scheduling order. Both
  together satisfy the task brief's explicit fallback ("if reproducing the exact counterfactual is
  impractical, at minimum confirm the topic exists... and that the bean is actually picked up at
  runtime").
- **Used `docker exec ... kafka-console-consumer.sh` directly against the live topic** rather than
  trusting `/actuator/beans` (which returned 404 — not exposed on this service) as the runtime-wiring
  proof, since a real dead-lettered record with correct headers is stronger evidence than a beans list
  would have been anyway.
- **Ignored the unrelated pending changes in the working tree** (`.claude/agents/*`,
  `docs/planning/README.md`, deleted `docs/study-guide/*`, etc. — visible in `git status` but not part
  of issue #41's diff) since they are out of scope for this verification and predate/postdate this
  session independently.

## Deliberately not covered

- **Did not independently force a fresh, live before/after collision** the way the implementer did,
  since doing so requires a source edit that's out of scope for a verifier. Relied instead on reading
  the implementer's own persisted DLQ record directly from the topic (independent of their prose) plus
  my own unsuccessful-as-expected reproduction attempts against the shipped code, per the task brief's
  own stated fallback for this scenario.
- **`onDlqRecord` (the second of `EventProjectionConsumer`'s two listeners) was not exercised at
  all**, live or otherwise, by either the implementer's report or this verification. Both listeners
  share the same auto-configured `CommonErrorHandler`, so there's no plausible code path for it to
  behave differently, but this remains an assumption, not a directly observed fact, in both accounts.
- **Did not audit the rest of the repo for other unwired `@KafkaListener`s** outside the five
  `*KafkaReliabilityConfig`-covered services — the report explicitly scoped this out as follow-up work
  too, and I did not independently re-scope it in.
- **Did not review the unrelated changes present in the working tree** (`.claude/agents/*` diffs,
  `docs/planning/README.md`, deleted `docs/study-guide/*` tree, `docs/external/claude-effort.md`
  deletion, `.gitignore`) — none are part of issue #41's diff and reviewing them is outside this
  verification's scope.
