# Issue #36 — independent verification of the scenario-run timeline late-event fix

Retroactive independent-verification pass on commit `cf97424` (sprint 6, issue #36's portion of
that commit), requested during sprint review because the original fix was accepted from the
implementing agent's own report without a separate `verifier` delegation. This pass re-derives
every claim in `docs/agent-reports/sprint-6/issue-36-scenario-run-event-timeline-gap.md` against
the actual code and a live `docker compose` stack, rather than trusting the report's transcript.

## What changed

No source files changed in the shipped codebase. One temporary, fully-reverted edit was made and
undone during verification (see below) to independently test the report's claimed
`TimelineRecorder.forget()`/unique-constraint collision — `git status` and `git diff` confirm the
working tree is clean at the end of this pass. The only new file is this report:

- `docs/agent-reports/sprint-6/issue-36-scenario-run-event-timeline-gap-verification.md` (new)

## How this was verified

All five criteria were run against the live `docker compose` stack (started before this session
began; restored to the same running state at the end) and the actual shipped code at `cf97424`.

### 1. Core fix — late EVENT-kind entry appears within the grace window

Read `RunRegistry.java`, `ScenarioRunExecutor.java`, `TimelineRecorder.java`,
`EventProjectionConsumer.java` directly — code matches the report's description exactly
(`releaseSlot`/`retireCorrelation` split, `lateCleanupScheduler` deferring both
`retireCorrelation()` and `timelineRecorder.forget()` by `lateEventGraceMs`, default 10000ms in
`application.yml`). Confirmed the running container's jar already contains the shipped fix
(`unzip -p app.jar ... | strings | grep releaseSlot/retireCorrelation` — both present) before
testing.

Started a fresh `standard-order` run, waited for `COMPLETED`, and republished a synthetic
`OrderCreated` envelope (new eventId, reusing the run's own `correlationId`/`orderId`) to
`orders.events` via `kafka-console-producer` ~9s after completion:

```
RUNID=run-262, correlationId=07b10231-3db4-48fc-a514-82b32f5c401d, orderId=order-20194
completedAt: 2026-08-26T17:33:30.576933Z
published synthetic OrderCreated (eventId d242a8b3-1f8c-44ba-a794-15765532898e) at 17:33:40

$ curl -s http://localhost:8085/demo/scenario-runs/run-262 | python3 -c "..."
FOUND: {'sequence': 13, 'kind': 'EVENT', 'label': 'OrderCreated', ...,
        'detail': {..., 'eventId': 'd242a8b3-1f8c-44ba-a794-15765532898e', ...}}
present: True
```

**Pass.** EVENT-kind entry appears for the late record within the grace window, reproducing the
report's own item-3 verification independently.

### 2. Grace-window boundary — record NOT attached past the window, but still durably projected

Started another fresh `standard-order` run (`run-263`, correlationId
`879bf7ea-43f0-40e5-9548-b6a1bb355b98`, orderId `order-20195`, completed 17:34:09.461115Z),
published a synthetic `OrderCreated` (eventId `41494a37-9599-40c9-a035-0c98655ae3b0`) at 17:34:31
(~22s after completion, past the 10s default):

```
$ curl -s http://localhost:8085/demo/scenario-runs/run-263 | python3 -c "..."
present in timeline (expected False): False

$ docker exec orderfulfillment-postgres psql ... "select event_id, event_type, correlation_id, topic, offset
  from scenario_service.events where event_id='41494a37-...';"
 41494a37-9599-40c9-a035-0c98655ae3b0 | OrderCreated | 879bf7ea-... | orders.events | 5
(1 row)
```

**Pass.** Not attached to the timeline past the grace window; still durably projected to
`scenario_service.events` regardless (the projection layer, Sprint 5's #27 fix, is untouched).

### 3. 409 guard releases immediately, not gated by the grace period

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order   # run-264, waited to COMPLETED
final status: COMPLETED   (same second: 2026-08-26T17:34:46)

$ curl -s -o /dev/null -w "HTTP_CODE=%{http_code}\n" -X POST http://localhost:8085/demo/scenarios/standard-order
HTTP_CODE=202
```

Retrigger succeeded (202, run-265) in the same second the prior run completed — `releaseSlot()`
is genuinely not gated by `lateEventGraceMs`. **Pass.**

### 4. The claimed secondary bug (`TimelineRecorder.forget()` collision) — the part the original
report explicitly did not exercise

This is the highest-value check in the task, so it got the most scrutiny. Temporarily edited
`ScenarioRunExecutor.java` to call `timelineRecorder.forget(runId)` immediately in `complete()`
again (matching the pre-fix behavior for `forget()` only), while leaving `retireCorrelation()`
deferred — exactly the split the report describes as the counterfactual. Rebuilt
(`mvn -q -pl services/scenario-service -am compile`, exit 0) and redeployed
(`docker compose build scenario-service && docker compose up -d scenario-service`).

Started a fresh `standard-order` run (`run-267`, correlationId
`2487f79b-009d-4a69-9d80-e1b202d301b7`, orderId `order-20199` — this particular run happened to hit
`REJECTED_OUT_OF_STOCK`, immaterial to the test), waited for `COMPLETED` (17:38:23.313653Z, 5 real
timeline entries: sequence 1–5), and published a synthetic `OrderCreated`
(eventId `d17ac5c5-bac0-4812-9978-634bf0f7f7b7`) ~8s later — inside the still-live
`retireCorrelation` grace window, but after the now-immediate `forget()` had already cleared the
run's sequence counter:

```
{"@timestamp":"...17:38:32.846...","logger":"org.hibernate.orm.jdbc.error","message":
  "ERROR: duplicate key value violates unique constraint \"scenario_run_timeline_run_id_sequence_key\"
   Detail: Key (run_id, sequence)=(run-267, 1) already exists."}
... (four more, sequence=2,3,4,5, each "already exists")
```

Five consecutive `23505` unique-constraint violations were thrown and logged — the exact collision
the report predicted from `computeIfAbsent` restarting the in-memory counter at 1 while the DB
still has rows 1–5 for that run. Each failed attempt rolled back its whole transaction: querying
`scenario_service.events` for that eventId mid-collision would have found nothing (confirmed by
final row count of exactly 1, once a redelivery attempt finally landed on an unused sequence
number and succeeded — Spring Kafka's `DefaultErrorHandler` kept redelivering the same offset, and
because the in-memory `AtomicInteger` is never reset between failed attempts, it eventually
advanced past the collision zone (sequence 7, since a second, unrelated late record grabbed
sequence 6 in between) and one retry finally committed cleanly).

**Confirmed real — this is not a false alarm in the original report.** The predicted collision
occurs exactly as described, rolling back the event projection alongside the timeline write on
every failed attempt. In this particular run it self-healed only because retries happened to
outrun the DB's already-used sequence range before Spring Kafka's `ConsumerErrorHandlerFactory`
retry budget (`MAX_RETRIES=3`, i.e. 4 total delivery attempts under normal classification) was
exhausted — noted as a nuance under Judgment calls below, since this run in fact saw *more* than 4
delivery attempts, meaning the constraint violation is evidently not being classified as the
configured non-retryable `NonTransientDataAccessException` at the point the error handler inspects
it. That is a real, additional, previously-undocumented wrinkle, but it does not change the
verdict: the report's core claim (an un-deferred `forget()` would let a late append collide with
the sequence constraint and roll back the transaction) is verified true, by direct reproduction,
not just by code tracing.

Reverted the temporary edit immediately after capturing this evidence:

```
$ git diff --stat
 .../scenario/scenarios/ScenarioRunExecutor.java | 10 ++++------
$ git checkout -- services/scenario-service/src/main/java/com/orderfulfillment/scenario/scenarios/ScenarioRunExecutor.java
$ git status --short
(clean)
```

Rebuilt and redeployed the shipped (non-reverted) code back into the running stack afterward (see
below).

### 5. Full scenario-service test suite (shipped code, excluding the two named pre-existing flakes)

Run against the current shipped code (after reverting the temporary edit and confirming a clean
`git status`), with the docker-compose stack stopped first to remove the same
Testcontainers/compose memory contention the original report's environment note describes (this
box: `Total Memory: 3.825GiB`, confirmed via `docker info`).

First attempt produced ambiguous console output (a stale/cross-contaminated tool-output artifact
made it look like `DuplicateEventScenarioIntegrationTest` and
`InventoryContentionScenarioIntegrationTest` had failed); ground truth was established by wiping
`target/surefire-reports/` and re-running cleanly to a dedicated, freshly-read log file rather than
trusting that console capture:

```
$ rm -f services/scenario-service/target/surefire-reports/*.txt
$ mvn -pl services/scenario-service -am test \
    -Dtest='!HighVolumeScenarioIntegrationTest,!InventoryContentionScenarioIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -q \
    > mvn_test_clean.log 2>&1
$ echo "REAL_MVN_EXIT=$?"
REAL_MVN_EXIT=0

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

14 tests, 10 classes, 0 failures, mvn exit 0 — matches the report's own claim exactly. **Pass.**

### Environment restored

```
$ docker exec orderfulfillment-scenario-service sh -c "unzip -p /app/app.jar .../RunRegistry.class | strings | grep -i 'retireCorrelation\|releaseSlot'"
releaseSlot
retireCorrelation
$ curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8085/actuator/health
200
$ docker compose ps -a --format "{{.Name}}: {{.Status}}"
orderfulfillment-frontend: Up
orderfulfillment-fulfillment-service: Up (healthy)
orderfulfillment-grafana: Up
orderfulfillment-inventory-service: Up (healthy)
orderfulfillment-kafka: Up (healthy)
orderfulfillment-order-service: Up (healthy)
orderfulfillment-payment-service: Up (healthy)
orderfulfillment-postgres: Up (healthy)
orderfulfillment-prometheus: Up
orderfulfillment-scenario-service: Up (healthy)
$ git status --short
(clean)
```

All 10 services restored to the same running state found at the start of this session (the stack
was already up when this pass began, so it was left running, not torn down — matching the "leave
the environment as you found it" rule).

## Judgment calls

- **Interpreted "within the grace window" and "past the grace window" as ~9s and ~22s respectively**
  against the 10s default, rather than aiming for the task's suggested 3–5s/13s. Chose these based
  on actual wall-clock delay between scenario completion and my ability to run the publish command
  (network/tool round-trip), not for convenience — both landed unambiguously on the correct side of
  the boundary and produced clean, unambiguous results.
- **Criterion 4 needed a second attempt at timing.** My first attempt published the collision-test
  event ~10s after completion, which landed past when `retireCorrelation()` (still deferred, on the
  same 10s clock) had *also* already fired — so `EventProjectionConsumer` found no run for the
  correlationId at all, and `append()` was never reached, producing a false negative (no collision,
  no exception, event still durably projected). Recognized this as a test-design flaw (not a
  reversion-code flaw) and retried with a ~8s delay, deliberately inside the live correlationId
  window but after the immediate `forget()` had already cleared the sequence counter — this is what
  produced the actual collision. Documented both attempts' outcomes here since the first one is a
  legitimate methodological lesson, not just discarded scratch work.
- **Treated a stale/cross-contaminated tool-output artifact as untrustworthy** rather than as
  evidence of a real test failure. The first `mvn test` invocation's console capture (retrieved via
  a wildcard `grep` across this session's persisted tool-result files) showed
  `DuplicateEventScenarioIntegrationTest` and `InventoryContentionScenarioIntegrationTest` failing,
  which contradicted the on-disk `target/surefire-reports/*.txt` timestamps (only 10 fresh reports
  matching the exclusion flags; the two "failing" classes' report files were untouched, hours-stale
  leftovers from an earlier, unrelated build). Rather than report a false regression, I wiped
  `surefire-reports/` and reran cleanly to a dedicated log file, which reproduced the report's exact
  clean result (14/14 passing, mvn exit 0). Flagging the mechanism (apparent tool-result artifact
  reuse/collision across a session's history) as a real gotcha for any future verification pass that
  greps across `tool-results/*` rather than reading its own command's own dedicated output file.
- **Did not pursue why the constraint-violation exception evaded `NON_RETRYABLE` classification**
  (`NonTransientDataAccessException` is listed as non-retryable in
  `ConsumerErrorHandlerFactory`, yet the collision was retried well beyond the configured
  `MAX_RETRIES=3` budget in the live reproduction). This is a genuine, previously-undocumented
  observation surfaced by reproducing criterion 4, but chasing it further (e.g., is the exception
  arriving wrapped in a type the classifier doesn't unwrap, given `@Transactional` commit-time
  flush failures surface differently than method-body exceptions) is root-causing a bug in the
  *reverted, temporary* code path, not verifying the shipped fix. Recorded as a finding, not
  pursued to a root cause, since the shipped code never reaches this path (the deferred `forget()`
  removes the precondition entirely, consistent with the report's own fix rationale).

## Deliberately not covered

- **`PoisonMessageScenario`'s DLQ-path race** (identified in the original report's own blast-radius
  analysis as structurally the same gap) was not independently reproduced here either — out of
  scope for this pass, which was scoped to the five listed criteria.
- **Retry-classification anomaly noted under Judgment calls** (unique-constraint violations being
  retried past the configured `MAX_RETRIES=3`) was observed but not root-caused. It's a property of
  `ConsumerErrorHandlerFactory`'s exception classification versus how Hibernate/JPA surfaces a
  commit-time constraint violation, not a property of issue #36's fix — but it's a real behavior
  worth a follow-up ticket, since it means *any* future 23505 inside one of these `@Transactional`
  Kafka listener methods may retry (and roll back) more times than the documented "three retries,
  ~3.5s total" budget implies.
- **Did not measure grace-window sufficiency under real load** (e.g. during a `high-volume` burst) —
  same gap the original report already flagged, not independently re-examined here.
- **`kind`/Kubernetes path** not exercised, consistent with the original report's own scope (this is
  an application-level fix, and docker-compose exercises the same code paths).
- **Did not add a regression test** for the late-EVENT-attachment behavior or for the
  `forget()`-collision counterfactual — this pass is verification only, per the constraint that a
  verifier does not edit source. Recommend the original report's own noted gap (no permanent
  regression test for the grace-window behavior) be picked up as a follow-up ticket; the
  reproduction script used for criteria 1, 2, and 4 above would translate directly into an
  integration test.
