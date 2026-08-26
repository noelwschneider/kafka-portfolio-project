# Issue #41 — `ConsumerErrorHandlerFactory` retry-past-budget bug

Reproduced and root-caused the defect the sprint-6 verification pass surfaced as a side finding: a
genuine Postgres `23505` unique-constraint violation, thrown inside a `@Transactional`
`@KafkaListener` method (`EventProjectionConsumer.onDomainRecord` in Scenario Service), retried well
past `ConsumerErrorHandlerFactory`'s documented `MAX_RETRIES=3` (~3.5s total) budget even though
`NonTransientDataAccessException` is on the classifier's non-retryable list.

**The working hypothesis in the task brief was wrong.** It suspected a commit-time flush failure
surfacing as a different, unclassified exception type (e.g. `TransactionSystemException`) that the
classifier's cause-chain walk doesn't unwrap. Direct reproduction disproves this: the real exception
reaching the point where classification would matter is exactly
`DataIntegrityViolationException` (a `NonTransientDataAccessException` subtype), unwrapped correctly.
**The classifier itself was never the bug.** The actual defect is structural: Scenario Service's
`EventProjectionConsumer` — the only `@KafkaListener` holder in that service — was never wired to
`ConsumerErrorHandlerFactory` at all. No `*KafkaReliabilityConfig` `@Configuration` class existed for
Scenario Service (every other service — order/inventory/payment/fulfillment — has one). With no
`CommonErrorHandler` bean in context, Spring Boot's Kafka auto-configuration silently fell back to
Spring Kafka's own framework default (`DefaultErrorHandler`'s no-arg constructor:
`FixedBackOff(0L, 9L)` — up to 10 immediate, zero-backoff redeliveries, no classification, no DLQ),
bypassing the shared policy entirely for both of that consumer's listeners
(`onDomainRecord`/`onDlqRecord`).

## Reproduction (before the fix)

Used the same counterfactual the sprint-6 verification report used (temporarily reverting
`ScenarioRunExecutor.complete()` to call `timelineRecorder.forget(runId)` immediately, decoupled from
the deferred `retireCorrelation()`) to reliably produce a genuine `23505` collision inside the live
`onDomainRecord` listener, plus a temporary `RetryListener` diagnostic log
(`DeliveryAttemptTracker.failedDelivery`) to see the actual exception chain and classification
decision on each attempt, plus a temporarily widened `late-event-grace-ms` (60s, via a
`docker-compose.yml` env override) to give slack for manual `docker exec`/`curl` round-trips against
the retireCorrelation window. All three were reverted before the fix was verified in its final form
(see "What changed" — none of them are in the final diff).

Started `run-270` (`standard-order`, correlationId `594295e3-...`), waited for `COMPLETED`
(`2026-08-26T20:28:28.565Z`, 5 timeline rows, sequence 1–5), then published a synthetic `OrderCreated`
(new eventId, same correlationId/orderId) directly to `orders.events` via
`kafka-console-producer` ~10s later:

```
$ docker compose logs scenario-service --since 3m | grep -c "duplicate key"
5
$ docker compose logs scenario-service --since 3m | grep "594295e3" | grep "duplicate key" | python3 -c "
import sys,json
for l in sys.stdin:
    d=json.loads(l[l.find('{'):]); print(d['@timestamp'], d['message'].splitlines()[0])"
2026-08-26T20:28:41.508Z ERROR: duplicate key value violates unique constraint "scenario_run_timeline_run_id_sequence_key" Detail: Key (run_id, sequence)=(run-270, 1) already exists.
2026-08-26T20:28:41.660Z ... sequence=(run-270, 2) already exists.
2026-08-26T20:28:41.687Z ... sequence=(run-270, 3) already exists.
2026-08-26T20:28:41.712Z ... sequence=(run-270, 4) already exists.
2026-08-26T20:28:41.753Z ... sequence=(run-270, 5) already exists.
```

Five collisions in **~250ms total**, not the configured 0.5s/1s/2s backoff — and **zero** hits on the
`DeliveryAttemptTracker.failedDelivery` diagnostic log (which only fires when
`ConsumerErrorHandlerFactory`'s `DefaultErrorHandler` is actually driving the retry). Both facts are
inconsistent with `ConsumerErrorHandlerFactory` handling this failure at all, and consistent with
Spring Kafka's own bytecode-confirmed default:

```
$ javap -p -c org/springframework/kafka/listener/SeekUtils.class | grep -A10 "static {}"
  static {};
    Code:
         0: new  #272  // class org/springframework/util/backoff/FixedBackOff
         3: dup
         4: lconst_0
         5: ldc2_w #274  // long 9l
         8: invokespecial #276 // FixedBackOff."<init>":(JJ)V
        11: putstatic #279  // DEFAULT_BACK_OFF
```

`grep -rl ConsumerErrorHandlerFactory services/*/src/main` confirmed the mechanism: it is used by
`OrderKafkaReliabilityConfig`, `InventoryKafkaReliabilityConfig`, `PaymentKafkaReliabilityConfig`,
`FulfillmentKafkaReliabilityConfig` and (in Scenario Service) only from a code comment in
`PoisonMessageScenario.java` describing *Inventory* Service's behavior — never from any
`@Configuration`/`@Bean` inside `scenario-service` itself.

## Root cause

`services/scenario-service` never had the `@Configuration` class every other service has
(`docs/reliability-pattern.md` §4.1/§8 checklist item 6: "One `@Configuration` with one
`DefaultErrorHandler` bean from `ConsumerErrorHandlerFactory.create(...)`"). Spring Boot's Kafka
auto-configuration applies whatever single `CommonErrorHandler` bean exists in the application
context to every listener container factory in that service — with none present, it falls back to
Spring Kafka's own framework default (`FixedBackOff(0, 9)`, no exception classification beyond Spring
Kafka's own built-ins, no `DeadLetterPublishingRecoverer`). This is not specific to
`NonTransientDataAccessException` — **every** failure in `EventProjectionConsumer`'s two listeners was
silently exempt from the documented retry/DLQ policy, not just constraint violations.

## The fix

Wired Scenario Service into the same shared policy every other service uses, following the existing
pattern exactly (`docs/reliability-pattern.md` §8's checklist, item 6):

- New `services/scenario-service/.../projection/ScenarioKafkaReliabilityConfig.java` — one `@Bean`
  calling `ConsumerErrorHandlerFactory.create(KafkaTopics.SCENARIO_DLQ)`, matching
  `OrderKafkaReliabilityConfig` et al.
- `ConsumerErrorHandlerFactory.create(...)` requires a destination DLQ topic, and none of the four
  existing `<domain>.dlq` topics is the right one — routing there would misattribute the failure to a
  domain service that did nothing wrong (`docs/events/event-catalog.md` §2's existing rule: "the
  failing consumer" owns the DLQ, and the failing consumer here is Scenario Service's own projection).
  Added `KafkaTopics.SCENARIO_DLQ = "scenario.dlq"` and a matching `NewTopic` bean in
  `KafkaTopicConfig`, both in `services/common` (shared infra every service already declares topics
  through).
- Followed the coordination protocol for the frozen `docs/events/event-catalog.md`: added the
  `scenario.dlq` row to §2's topic table, and logged the change in `docs/CHANGELOG-contracts.md`.
- Added one paragraph to `docs/reliability-pattern.md` §4.1 stating that this pattern now covers
  every `@KafkaListener` in the system, not only the four domain services' own listeners, and why a
  missing `*KafkaReliabilityConfig` bean is a silent fallback rather than a startup error.

No change was made to `ConsumerErrorHandlerFactory.java` itself — its classification logic was
confirmed correct once actually invoked (see verification below).

## Fix confirmed live (after)

Same reproduction technique, same counterfactual, against the rebuilt image with
`ScenarioKafkaReliabilityConfig` in place. Started `run-271`, waited for `COMPLETED`
(`2026-08-26T20:36:43.639Z`), published a synthetic `OrderCreated` for it ~14s later:

```
$ docker compose logs scenario-service --since 2m | grep ISSUE_41_REPRO
2026-08-26T20:36:58.809Z ISSUE-41 attempt=1 record=orders.events-1@9
  exceptionChain=ListenerExecutionFailedException -> DataIntegrityViolationException ->
  ConstraintViolationException -> PSQLException retryable=false
2026-08-26T20:36:59.152Z ISSUE-41 attempt=1 record=inventory.events-1@7
  exceptionChain=ListenerExecutionFailedException -> DataIntegrityViolationException ->
  ConstraintViolationException -> PSQLException retryable=false

$ docker compose logs scenario-service --since 2m | grep "Dead-lettering"
... "Dead-lettering orders.events-1@9 to scenario.dlq after 1 delivery attempt(s) (non-retryable failure)"
... "Dead-lettering inventory.events-1@7 to scenario.dlq after 1 delivery attempt(s) (non-retryable failure)"

$ docker exec orderfulfillment-kafka kafka-console-consumer.sh --bootstrap-server localhost:29092 \
    --topic scenario.dlq --from-beginning --property print.headers=true --max-messages 2 --timeout-ms 8000
kafka_dlt-exception-fqcn:org.springframework.kafka.listener.ListenerExecutionFailedException,
kafka_dlt-exception-cause-fqcn:org.springframework.dao.DataIntegrityViolationException, ...
```

Exactly **1 delivery attempt**, classified `retryable=false`, dead-lettered to the new `scenario.dlq`
with correct exception metadata — not 5+ rapid-fire redeliveries with no DLQ. Confirms both that the
classifier logic was already correct and that the wiring gap was the actual defect.

Also confirmed the fix does not regress the normal (non-colliding) path: a fresh `standard-order` run
against the fixed image shows both its `EVENT`-kind timeline entries populated normally
(`GET /demo/scenario-runs/run-272`, sequence 3 `OrderCreated` and sequence 5
`InventoryReservationFailed`, both present with full detail).

## What changed

- `services/common/src/main/java/com/orderfulfillment/common/kafka/KafkaTopics.java` — added
  `SCENARIO_DLQ = "scenario.dlq"` constant.
- `services/common/src/main/java/com/orderfulfillment/common/kafka/KafkaTopicConfig.java` — added
  `scenarioDlqTopic()` `NewTopic` bean (3 partitions, replication 1, matching the four existing DLQ
  topics).
- `services/scenario-service/src/main/java/com/orderfulfillment/scenario/projection/ScenarioKafkaReliabilityConfig.java`
  (new) — the one `@Bean` that wires `ConsumerErrorHandlerFactory.create(KafkaTopics.SCENARIO_DLQ)`
  into Scenario Service, the actual fix.
- `docs/events/event-catalog.md` — added `scenario.dlq` row to §2's topic table (frozen contract,
  changed via the coordination protocol).
- `docs/CHANGELOG-contracts.md` — new entry recording the `scenario.dlq` addition and why.
- `docs/reliability-pattern.md` — one paragraph in §4.1 stating this pattern covers every
  `@KafkaListener` including Scenario Service's, and that a missing `*KafkaReliabilityConfig` bean
  silently falls back to Spring Kafka's own default rather than failing loudly.
- `ConsumerErrorHandlerFactory.java` — **not changed**. Its classification logic was confirmed
  correct; the defect was entirely in what was (not) wired to it.

**Temporary, fully reverted during this session (not in the final diff — confirmed via `git status`/`git diff`
showing no changes to these three):**
- `services/scenario-service/.../ScenarioRunExecutor.java` — briefly reverted `complete()` to call
  `timelineRecorder.forget(runId)` immediately (the same counterfactual the sprint-6 verification used)
  to reliably reproduce the collision; reverted back to the shipped, deferred-forget behavior before
  the fix was verified in its final form.
- `services/common/.../DeliveryAttemptTracker.java` — briefly added an `ISSUE_41_REPRO` diagnostic log
  line in `failedDelivery()` to observe the real exception chain and classification decision per
  attempt; removed afterward.
- `docker-compose.yml` — briefly added
  `ORDERFULFILLMENT_SCENARIO_LATE_EVENT_GRACE_MS: "60000"` to `scenario-service`'s environment, to
  give manual repro commands slack against the 10s default; removed afterward.

## How this was verified

```
$ git diff --stat -- services/ docker-compose.yml docs/events/event-catalog.md docs/CHANGELOG-contracts.md docs/reliability-pattern.md
 docs/CHANGELOG-contracts.md                                              | 28 ++++++++++++++++++++++
 docs/events/event-catalog.md                                             |  1 +
 docs/reliability-pattern.md                                              |  8 +++++++
 .../common/kafka/KafkaTopicConfig.java                                   |  8 +++++++
 .../orderfulfillment/common/kafka/KafkaTopics.java                       |  9 +++++++
 5 files changed, 54 insertions(+)
$ git status --short | grep -E "DeliveryAttemptTracker|ScenarioRunExecutor|docker-compose.yml"
(no output — confirms all three temporary/diagnostic edits are fully reverted)
```

Build (with the real fix, diagnostics removed):

```
$ docker compose build scenario-service
...
#12 [build 5/6] RUN mvn -q -pl services/common,services/scenario-service -am package -DskipTests
#12 DONE 55.0s
 Image kafka-portfolio-project-scenario-service Built
$ docker compose up -d scenario-service
 Container orderfulfillment-scenario-service Started
$ curl -s http://localhost:8085/actuator/health
{"status":"UP", ...}
```

New topic exists with correct shape:

```
$ docker exec orderfulfillment-kafka kafka-topics.sh --bootstrap-server localhost:29092 --describe --topic scenario.dlq
Topic: scenario.dlq	TopicId: jzg3yXLNR2KPdt2jNzcjTw	PartitionCount: 3	ReplicationFactor: 1
```

Full scenario-service test suite against the fixed image (same exclusions sprint-6's verification
used — two pre-existing, unrelated flakes under memory contention on this box):

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
```

`services/common` tests, run separately since `KafkaTopics`/`KafkaTopicConfig` changed:

```
$ mvn -pl services/common -am test -q
$ echo EXIT=$?
EXIT=0
```

Live before/after reproduction of the actual defect and its fix: see the "Reproduction (before the
fix)" and "Fix confirmed live (after)" sections above — both against the real running
`docker compose` stack, not a unit test.

Environment restored at the end:

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
orderfulfillment-scenario-service: Up (healthy)  # rebuilt/redeployed with the fix, still healthy
```

The stack was already running (started before this session, ~3 hours uptime on the untouched
services) and is left running, not torn down, per the "leave the environment as you found it" rule.
Only `scenario-service` was rebuilt/redeployed (three times total: once to add reproduction
diagnostics, once to add the real fix alongside those diagnostics for a same-conditions before/after
comparison, once more to remove the diagnostics for the final clean state) — the other four
application services were never rebuilt, since nothing about their behavior changed (see Judgment
calls).

## Judgment calls

- **Rejected the task brief's working hypothesis rather than trying to confirm it.** The brief
  suspected a commit-time flush wrapping the exception in something the classifier's cause-chain walk
  doesn't unwrap. Before touching `ConsumerErrorHandlerFactory`, I reproduced the failure with a
  `RetryListener` diagnostic that logs the literal exception chain reaching the point classification
  would happen, and it showed a plain, correctly-unwrappable `DataIntegrityViolationException` chain
  — and, more tellingly, showed *no* retry-listener callbacks at all for a failure that was clearly
  being retried multiple times. That absence, plus the sub-250ms total retry window (inconsistent
  with the configured 500ms/1s/2s backoff), was the real signal: something other than
  `ConsumerErrorHandlerFactory`'s `DefaultErrorHandler` was driving these retries. Chased that instead
  of the brief's hypothesis, which is how the actual (unwired listener) root cause was found. Reported
  explicitly as a rejected hypothesis rather than silently substituting a different fix, per "a
  plausible explanation is not a diagnosis."
- **Added a new `scenario.dlq` topic rather than reusing an existing one or skipping DLQ routing.**
  `ConsumerErrorHandlerFactory.create(...)`'s signature requires a destination topic — there is no way
  to get its classification/backoff behavior without also getting a `DeadLetterPublishingRecoverer`
  pointed somewhere. Reusing e.g. `orders.dlq` was rejected because it would misattribute Scenario
  Service's own projection failures to Order Service, violating the existing "the failing consumer
  owns the DLQ" rule the four domain DLQs already establish. A new, service-owned DLQ was the only
  option consistent with the existing pattern, so I followed the coordination protocol (propose in
  `event-catalog.md`, implement, log in `CHANGELOG-contracts.md`) rather than working around it
  locally.
- **Reused the exact sprint-6-verification counterfactual (`forget()` called immediately) to
  reproduce the collision**, rather than inventing a new reproduction path, since it was already
  proven to reliably produce a genuine `23505` inside this listener and the task explicitly asked me
  to confirm the sprint-6 account against the artifact rather than trust it. Widened
  `late-event-grace-ms` to 60s via a temporary `docker-compose.yml` env override (rather than editing
  `application.yml`, to keep the change one `docker compose up -d` away from reverting) because manual
  `docker exec`/`curl` round-trips repeatedly missed the 10s default window on the first two attempts
  (see the raw session transcript) — confirmed by direct observation, not assumed, since the first two
  attempts produced a durably-projected-but-never-appended event (correctly showing the mechanism the
  sprint-6 report itself flagged as a timing trap in criterion 4).
- **Did not rebuild or redeploy order/inventory/payment/fulfillment services**, even though they
  depend on the same `services/common` module I changed. `KafkaTopics.SCENARIO_DLQ` and the new
  `NewTopic` bean are purely additive — no existing constant, bean, or classification behavior changed
  — so their currently-running containers (on the pre-change `common` jar) are functionally identical
  to what a rebuild would produce for anything they actually do. Rebuilding them would have been
  either the "shared fix requires it" case, which this isn't, or unnecessary churn against production
  advice to only touch what a fix genuinely requires.
- **Did not add a permanent regression test** for "an unclassified/non-retryable failure inside
  `EventProjectionConsumer` is dead-lettered to `scenario.dlq` on the first attempt." Time-boxed this
  session toward reproducing, root-causing, and fixing the live defect with direct evidence at each
  step; a `PoisonMessageScenarioIntegrationTest`-style permanent test for Scenario Service's own DLQ
  path is real follow-up work (see Deliberately not covered).
- **Left the one-line mention of Scenario Service in `docs/reliability-pattern.md` minimal** rather
  than rewriting §8's checklist to formally include Scenario Service as a "fan-out service" — that
  section's framing (`processed_events`, idempotency claims) genuinely doesn't apply to a read-only
  projection consumer, so folding it into that checklist verbatim would overstate what Scenario
  Service actually needs from the pattern (just the error handler, not the whole idempotent-consumer
  apparatus).

## Deliberately not covered

- **No permanent automated regression test added** for this fix. `docs/scenarios.md`'s Scenario 6
  (poison-message) already exercises `ConsumerErrorHandlerFactory` end-to-end for the four domain
  services via `PoisonMessageScenarioIntegrationTest`; Scenario Service's own projection consumer has
  no equivalent poison-message test asserting it reaches `scenario.dlq`. Recommend a follow-up test
  (e.g. `ScenarioProjectionPoisonMessageIntegrationTest`, publishing a record whose projection write
  provably violates a constraint or throws `IllegalArgumentException`, asserting exactly one delivery
  attempt and a `scenario.dlq` record with correct headers) modeled directly on this session's manual
  reproduction.
- **`onDlqRecord`, the second of `EventProjectionConsumer`'s two listeners, was not independently
  reproduced** — only `onDomainRecord` was exercised live. Both listeners share the same
  `ScenarioKafkaReliabilityConfig` bean (Spring Boot applies one `CommonErrorHandler` to every
  listener in the service, confirmed by reading the Javadoc precedent in `OrderKafkaReliabilityConfig`
  and by this fix's own mechanism), so there is no code path by which `onDlqRecord` could behave
  differently, but it was not independently observed failing and recovering the way `onDomainRecord`
  was.
- **Did not audit whether any other service has a similarly-unwired `@KafkaListener`** outside a
  `*KafkaReliabilityConfig`-covered container factory (e.g. a listener added later on a distinct,
  explicitly-named container factory that bypasses the single auto-configured `CommonErrorHandler`).
  Scoped this session to Scenario Service, since that's where the bug was actually observed and
  reproduced; a repo-wide audit of every `@KafkaListener`'s container factory wiring is a reasonable
  but separate follow-up.
- **Did not investigate why Scenario Service was built without this wiring in the first place**
  (whether Phase 5's original build genuinely didn't know about the checklist, or deliberately
  scoped it out as "just a projection, retries don't matter here"). `EventProjectionConsumer`'s own
  Javadoc and the Phase 5 report it cites say nothing about intentionally opting out of the reliability
  pattern, and the observed behavior (silent, unbounded-looking rapid retries with no DLQ) is not
  something any design note frames as intentional, so I treated it as an oversight rather than a
  design decision to preserve — but I did not go looking for a Phase 5 planning doc that might
  explicitly justify the omission.
- **`kind`/Kubernetes path not exercised** — this is an application-level Spring/Kafka wiring fix,
  and `docker compose` exercises the identical code paths (same jars, same Kafka wire protocol).
