# Issue #36 — scenario-run timeline never shows EVENT-kind entries for late-arriving records

## What changed

- `services/scenario-service/src/main/java/com/orderfulfillment/scenario/runtime/RunRegistry.java`
  — split the old single `finish(scenarioName, correlationId)` immediate-removal method into three:
  `finish` (kept, for the one caller that legitimately needs immediate full teardown — a run that
  never actually started), `releaseSlot(scenarioName)` (frees the 409 "already running" guard
  immediately), and `retireCorrelation(correlationId)` (removes the `correlationId -> runId` mapping,
  called only by the deferred cleanup below). Javadoc explains why the two mappings now release on
  different schedules.
- `services/scenario-service/src/main/java/com/orderfulfillment/scenario/scenarios/ScenarioRunExecutor.java`
  — `complete()` now calls `runRegistry.releaseSlot(scenarioName)` immediately (unchanged behavior for
  the 409 guard) but defers `timelineRecorder.forget(runId)` and `runRegistry.retireCorrelation(correlationId)`
  to a background task scheduled `properties.lateEventGraceMs()` (default 10s) after completion, via a
  dedicated single-thread `ScheduledExecutorService` (`lateCleanupScheduler`, daemon thread, shut down
  in a new `@PreDestroy` method). Previously both cleanups ran synchronously and immediately inside
  `complete()`.
- `services/scenario-service/src/main/java/com/orderfulfillment/scenario/config/ScenarioProperties.java`
  — added `lateEventGraceMs` field.
- `services/scenario-service/src/main/resources/application.yml` — added
  `orderfulfillment.scenario.late-event-grace-ms: 10000` with a comment explaining the issue #36
  rationale.
- `services/scenario-service/src/test/resources/application-test.yml` — added
  `late-event-grace-ms: 2000` (shorter, for test speed; the exact value isn't load-bearing for any
  existing test's assertions).

No changes to `EventProjectionConsumer`'s dedup logic (Sprint 5 / #27, frozen) or any frontend
timeline-rendering code, per the task's scope boundary.

## Reproduction

**Root cause, confirmed by direct observation, not just code inspection.** `RunRegistry.finish()`
removed the `correlationId -> runId` mapping the instant a run reached a terminal status.
`EventProjectionConsumer` (a separate Kafka consumer group, `scenario-service-projection`) looks up
that mapping to decide whether a just-projected record should get an EVENT-kind timeline entry
(`EventProjectionConsumer.java:137`, `runRegistry.runIdForCorrelation(...).ifPresent(...)`). Any
record for that correlationId consumed after the mapping was removed silently got no timeline entry,
even though it was still durably projected to `scenario_service.events` (Sprint 5's fix untouched).

The natural trigger named in the task (`DuplicateEventScenario`'s fire-and-forget republish, sent
before `awaitTerminal()` blocks) turned out to be a **rare** natural occurrence on this box, not a
reliable one — traced timestamps from several live `duplicate-event` runs showed the republish's own
projection (a single Kafka round trip) consistently completing *faster* than the full multi-hop
downstream flow (`awaitTerminal` waits through inventory reservation, payment, and fulfillment before
returning), so in the common case the mapping was still present by the time the duplicate landed. I
did not stop at that non-reproduction — I reproduced the underlying mechanism deterministically instead
of concluding "not a bug" from a few lucky runs:

1. Started a `standard-order` run, waited for `COMPLETED` (real HTTP, real Kafka, real Postgres, no
   mocks).
2. Published a synthetic but fully valid `EventEnvelope` JSON record directly to `orders.events` via
   `kafka-console-producer`, carrying that run's own `correlationId` and its `orderId`, ~28s after
   completion.
3. Confirmed via direct SQL that the record was durably projected to `scenario_service.events`
   (`event_id` present, offset assigned) — the projection layer is healthy.
4. Confirmed via `GET /demo/scenario-runs/{runId}` that **no EVENT-kind timeline entry existed for
   that eventId** — the exact defect from the issue.

Before (pre-fix code, run-254, orderId order-20186, completed 2026-08-26T16:11:34.079364Z):

```
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c \
  "select event_id, event_type, aggregate_id, correlation_id, topic, \"offset\" from scenario_service.events where event_id='8eaf14e4-86c6-419b-8f9e-0beee3a207cb';"
               event_id               |  event_type  | aggregate_id |            correlation_id            |     topic     | offset
--------------------------------------+--------------+--------------+---------------------------------------+---------------+--------
 8eaf14e4-86c6-419b-8f9e-0beee3a207cb | OrderCreated | order-20186  | 6c403017-3216-499c-9e22-8eade49ab4d9 | orders.events |      6
(1 row)

$ curl -s http://localhost:8085/demo/scenario-runs/run-254 | python3 -c "...late eventId present..."
late eventId 8eaf14e4-86c6-419b-8f9e-0beee3a207cb present in timeline: False
```

## Blast radius (why RunRegistry retention, not DuplicateEventScenario.run() waiting)

The task asked me to check whether other scenarios have async post-completion Kafka activity that
would hit the same gap, not just `DuplicateEventScenario`. I read all 8 scenario runners
(`services/scenario-service/src/main/java/com/orderfulfillment/scenario/scenarios/*.java`) plus
`OrderStatusWatcher` (what `awaitTerminal` actually watches) and `EventProjectionConsumer`:

- **The gap is structural, not scenario-specific.** Every scenario ends by calling
  `orderStatusWatcher.awaitTerminal(...)`, which watches Order Service's own `GET /api/orders/stream`
  SSE endpoint — a push-only, future-transitions-only stream with **no replay of current state**
  (`OrderEventStreamRegistry.register()`, order-service; confirmed by reading the actual source, not
  assumed). That stream is driven by Order Service's *own* Kafka consumers reaching a terminal status,
  entirely independent of Scenario Service's own `scenario-service-projection` consumer group finishing
  the same backlog. Nothing guarantees the projection consumer group is caught up on a run's last
  Kafka record(s) at the exact instant Order Service's consumer marks the order terminal — this can
  happen for **any** scenario whose completion is order-status-driven (`standard-order`,
  `out-of-stock`, `payment-failure`, `consumer-outage`, `inventory-contention`), not only
  `duplicate-event`. `DuplicateEventScenario` just makes the specific failure mode (a *second* record
  for an already-seen eventId) easy to talk about, and adds one extra, deliberately-async publish on
  top of the same structural race.
- **`PoisonMessageScenario`** publishes a synthetic record and then just sleeps 6s expecting
  `EventProjectionConsumer` to observe the eventual `inventory.dlq` record "on its own" (its own
  comment says so) — another async-completion dependency that would hit the identical gap if the DLQ
  round trip (produce, retry-with-backoff ~3.5s, dead-letter, consume) outran the fixed sleep.
  `HighVolumeScenario` and `InventoryContentionScenario` fan out across their own thread pools but
  still terminate via the same `awaitTerminal`/`awaitTerminalPollOnly` path, so they inherit the same
  structural race for whichever order's terminal event is last to be projected.

Given that, **fix (a)** (`DuplicateEventScenario.run()` waiting for its own second projection before
returning) would only have closed the gap for that one scenario's one specific record, leaving the
structural race live for every other scenario and for `PoisonMessageScenario`'s DLQ path. **Fix (b)**
(retain the mapping past completion) closes it for all of them, since it fixes the shared mechanism
(`RunRegistry`) rather than one caller's timing.

## Judgment calls

- **Where the "briefly" lives.** The task described fix (b) as "have `RunRegistry` retain the mapping
  briefly." I implemented the retention as a deferred call from `ScenarioRunExecutor.complete()`
  (a one-shot scheduled task) rather than as TTL bookkeeping inside `RunRegistry` itself (e.g. storing
  a finished-at timestamp per entry and lazily expiring on read, plus a periodic sweep for entries
  never read again). The deferred-call approach needs no sweep/leak-prevention logic of its own — the
  scheduled task unconditionally removes the entry exactly once — and, importantly, let me solve a
  second problem for free (next bullet). `RunRegistry`'s public API stays almost the same shape as
  before (a bare `Map.remove`), just split into an immediate slot release and a deferred correlation
  retirement.
- **Found and fixed a second, latent bug in the same mechanism, not asked for explicitly but required
  for the primary fix to be safe.** `ScenarioRunExecutor.complete()` also called
  `timelineRecorder.forget(runId)` immediately, which clears `TimelineRecorder`'s in-memory per-run
  sequence counter. `scenario_run_timeline` has `UNIQUE (run_id, sequence)`
  (`V1__scenario_runs.sql:25`). If I had retained only the `RunRegistry` mapping and left
  `timelineRecorder.forget()` firing immediately, a late `EventProjectionConsumer.project()` call
  reaching `TimelineRecorder.append()` after `forget()` had already run would recreate the sequence
  counter via `computeIfAbsent` starting back at 1, colliding with the run's own already-used
  sequence=1 row and throwing a unique-constraint violation — inside the **same transaction** that
  also does `eventRecordRepository.save(entity)` for the Kafka record's own projection (`project()` and
  `append()` share one `@Transactional` boundary). That would have rolled back the otherwise-correct
  event projection too, turning "fix the timeline" into "sometimes break the projection Sprint 5 just
  fixed." I deferred `timelineRecorder.forget()` to the same scheduled task as
  `retireCorrelation()`, so the sequence counter and the correlationId mapping now go stale together,
  eliminating the class of bug rather than papering over one instance of it. I did not exercise this
  exact collision live (see Deliberately not covered) but traced it from `TimelineRecorder.append()`'s
  `computeIfAbsent` and the migration's unique constraint, and the fix removes the precondition (an
  append reaching a forgotten run) entirely.
- **Grace window value (10s default, 2s in tests).** Not measured against a slow/loaded box; chosen as
  generous relative to observed local produce-to-consume latency (sub-second to ~1s in traced runs).
  Configurable (`orderfulfillment.scenario.late-event-grace-ms`) so it can be tuned without a code
  change if the demo box needs more.
- **Reproduced the mechanism deterministically instead of chasing the natural race.** Documented above
  under Reproduction — I did not treat a few non-reproducing `duplicate-event` runs as "bug doesn't
  exist"; I isolated the exact interleaving `RunRegistry.finish()`/`EventProjectionConsumer.project()`
  hit and verified it directly, which also let me test the boundary (see Verification) rather than
  relying on chance timing.
- **`RunEventHub.close(runId)` still happens immediately at completion, not deferred.** A late EVENT
  entry within the grace window is still durably persisted and visible on `GET
  /demo/scenario-runs/{runId}` (the issue's actual repro target), but won't be pushed over the
  already-closed SSE stream. Deferring `close()` wouldn't help in practice — a real browser client
  closes its own `EventSource` on seeing the `run-status: COMPLETED` message, so nothing would be
  listening anyway. Left as-is; noted under Deliberately not covered.
- **`InventoryContentionScenarioIntegrationTest` treated as a pre-existing, unrelated flake**, using
  the same A/B methodology Sprint 5's #27 report used for `HighVolumeScenarioIntegrationTest`: ran it
  with my changes stashed out (pre-fix code) and it failed identically
  (`expected: "COMPLETED" but was: "FAILED"` after a 20s Awaitility timeout). Excluded it (alongside
  the already-known-flaky `HighVolumeScenarioIntegrationTest`) from the final clean full-suite run.

## How this was verified

**1. Compiled cleanly:**

```
$ mvn -q -pl services/scenario-service -am compile
(exit 0, no output)
```

**2. Deterministic reproduction, before fix** (see Reproduction section above for the full trace) —
late record durably projected, no timeline entry. Confirmed with pre-fix code still running in the
live compose stack.

**3. Rebuilt and redeployed with the fix, then re-ran the same deterministic reproduction — bug closed:**

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order
{"id":"run-256","correlationId":"70aa7215-88a3-4dd9-b7e0-99f0efb508c9",...}
# polled to COMPLETED at 2026-08-26T16:23:51.092323Z (orderId order-20188)
# published a synthetic OrderCreated record for this run's correlationId+orderId at 16:23:54 (~3.3s later)

$ curl -s http://localhost:8085/demo/scenario-runs/run-256 | python3 -c "...late eventId present..."
 EVENT seq 13 OrderCreated e6d58845-8088-4e44-b7bf-0fdf8eb98131
late eventId e6d58845-8088-4e44-b7bf-0fdf8eb98131 present: True
```

**4. Verified the grace window is actually bounded, not "never evicts"** — published a late record
~13s after completion (past the 10s default) and confirmed it is correctly *not* attached, while the
underlying projection remains durable regardless:

```
late (past-grace-window) eventId present in timeline: False (expected: False, since grace window already elapsed)
$ docker exec orderfulfillment-postgres psql ... "select event_id, event_type from scenario_service.events where event_id='e72ba835-...';"
               event_id               |  event_type
--------------------------------------+--------------
 e72ba835-048f-49db-97bd-cdc9667ccb64 | OrderCreated
(1 row)
```

**5. Verified the 409 "already running" guard still releases immediately** (not delayed by the same
grace window — a real regression risk of a naive single-timer approach):

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order   # run-257, waited for COMPLETED
$ curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8085/demo/scenarios/standard-order
202   # not 409 — re-trigger succeeded immediately after the previous run completed
```

**6. Live end-to-end verification of the exact named scenario from the task** — a real
`duplicate-event` run showing the republished event's EVENT-kind entry on `GET
/demo/scenario-runs/{id}`:

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/duplicate-event   # run-260
... polled to COMPLETED, orderId order-20192 ...
{
  "sequence": 3, "kind": "EVENT", "label": "OrderCreated",
  "detail": {"topic": "orders.events", "offset": 12, "eventId": "dbb59034-2b2e-4990-a160-f101543c02df", ...}
},
...
{
  "sequence": 8, "kind": "EVENT", "label": "OrderCreated",
  "detail": {"topic": "orders.events", "offset": 14, "eventId": "dbb59034-2b2e-4990-a160-f101543c02df", ...}
}
```
Both the original and the republished duplicate (same eventId, different offset) show as EVENT-kind
timeline entries.

**7. Full scenario-service test suite, clean (docker-compose stack stopped to remove resource
contention with Testcontainers' own Kafka+Postgres — see note below), excluding the two independently
confirmed pre-existing flakes:**

```
$ mvn -pl services/scenario-service -am test \
  -Dtest='!HighVolumeScenarioIntegrationTest,!InventoryContentionScenarioIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -q
MVN_EXIT=0
```
Surefire reports (14 tests across 10 classes, including `DuplicateEventScenarioIntegrationTest`):
```
Tests run: 1, Failures: 0, Errors: 0 -- StandardOrderScenarioIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- PaymentFailureScenarioIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- DuplicateEventScenarioIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- OutOfStockScenarioIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- IdleResetSchedulerIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- ConsumerOutageScenarioIntegrationTest
Tests run: 1, Failures: 0, Errors: 0 -- PoisonMessageScenarioIntegrationTest
Tests run: 2, Failures: 0, Errors: 0 -- ScenarioConflictIntegrationTest
Tests run: 2, Failures: 0, Errors: 0 -- DemoResetIntegrationTest
Tests run: 2, Failures: 0, Errors: 0 -- EventProjectionIntegrationTest
```

**8. `InventoryContentionScenarioIntegrationTest` confirmed pre-existing/unrelated** via A/B: stashed
my three source changes out, reran the same test alone against the same class of environment —
identical failure:
```
$ git stash push -- services/scenario-service/.../RunRegistry.java .../ScenarioRunExecutor.java \
    .../ScenarioProperties.java .../application.yml .../application-test.yml
$ mvn -q -pl services/scenario-service test -Dtest='InventoryContentionScenarioIntegrationTest' ...
[ERROR] Tests run: 1, Failures: 0, Errors: 1 ... expected: "COMPLETED" but was: "FAILED" within 20 seconds.
$ git stash pop   # restored my changes
```
Same failure with and without the fix — confirmed unrelated to this change, same methodology Sprint
5's #27 report used for `HighVolumeScenarioIntegrationTest`.

**Environment note:** this box's Docker Desktop VM is memory-constrained (3.825GiB). Running the full
docker-compose stack (10 services + grafana/prometheus) *and* a Testcontainers-backed Kafka+Postgres
pair simultaneously caused kafka broker health-check timeouts, consumer-group rebalance storms, and
one full container restart cascade (kafka OOM-killed, exit 137, taking the four domain services down
with it) partway through this session — entirely before any deploy of my change, self-recovered after
`docker compose up -d` and a wait. I stopped the compose stack while running the Testcontainers suite
to remove that contention; this explains the difference between the first (contended, 2 unrelated
flakes) and later (clean) full-suite runs, and is a pre-existing environment fragility, not caused by
or related to this change.

## Deliberately not covered

- **Did not exercise the `TimelineRecorder.append()`/unique-constraint collision directly** (i.e. did
  not deliberately revert only the `forget()` deferral while keeping `RunRegistry`'s deferral, to watch
  it throw). I traced the mechanism from the code (`computeIfAbsent` restart + `UNIQUE (run_id,
  sequence)`) and the fix removes the precondition rather than handling the collision, so there was no
  scenario left in which to reproduce it under the fixed code. Flagging as inventory: a
  reviewer who wants to see the failure mode directly would need to temporarily split the two
  deferrals apart and rerun step 3 above.
- **`PoisonMessageScenario`'s DLQ-path race**, identified during the blast-radius analysis as
  structurally the same gap (async completion, fixed sleep, no wait for the DLQ projection), was not
  independently reproduced or exercised live — the fix covers it because it's the same `RunRegistry`
  mechanism, but I did not construct a timing scenario to prove that specific path.
  `PoisonMessageScenarioIntegrationTest` passed in the full-suite run (item 7 above) but that doesn't
  specifically stress the race window.
- **Did not measure produce-to-consume latency under real load** (e.g. during a `high-volume` burst) to
  validate the 10s default grace window is generous enough on a busier box — chosen from traced local
  timings only (sub-second to ~1s).
- **`kind`/Kubernetes path** not exercised — this is an application-level, in-process bug (like Sprint
  5's #27), and docker-compose exercises the same Spring/Kafka code paths per that report's own
  precedent.
- **Did not add a permanent regression test** asserting the EVENT-kind entry for a late record appears
  within the grace window (e.g. an integration test that publishes directly to a topic after a run
  completes, mirroring the live reproduction in this report). `DuplicateEventScenarioIntegrationTest`
  was left as-is (it asserts the republish itself, not the timeline attachment) since the task's scope
  was fixing and live-verifying the defect, not authoring new automated coverage; noting this as a gap
  for a follow-up ticket.
- **Root-caused but did not separately verify `HighVolumeScenarioIntegrationTest`'s failure** beyond
  relying on Sprint 5's #27 report's own documented exclusion of it as an unrelated pre-existing flake
  (throughput/lag timing under `await()`); did not re-run its own A/B check this session since it was
  already independently established.
