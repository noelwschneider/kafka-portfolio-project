# Issue #27 — Scenario Service event-projection dedupe breaks on Kafka offset reset

## What changed

- `docker-compose.yml` — gave the `kafka` service a named volume
  (`orderfulfillment-kafka-data:/tmp/kraft-combined-logs`, the image's `log.dirs`), matching
  postgres's existing persistent-volume treatment, and registered
  `orderfulfillment-kafka-data` under top-level `volumes:`. Without this, every
  `docker compose up --build` recreated the kafka container with an empty log dir and reset every
  topic's offsets to 0.
- `services/scenario-service/src/main/resources/db/migration/V3__events_dedupe_by_topic_partition_offset_and_event_id.sql`
  (new) — drops the old `UNIQUE (topic, "partition", "offset")` constraint on
  `scenario_service.events` and replaces it with
  `UNIQUE (topic, "partition", "offset", event_id)`.
- `services/scenario-service/src/main/java/com/orderfulfillment/scenario/domain/EventRecordRepository.java`
  — replaced `existsByTopicAndPartitionAndOffset` with
  `existsByTopicAndPartitionAndOffsetAndEventId`.
- `services/scenario-service/src/main/java/com/orderfulfillment/scenario/projection/EventProjectionConsumer.java`
  — `project()`'s dedupe check now calls `existsByTopicAndPartitionAndOffsetAndEventId(topic,
  partition, offset, eventId)` instead of the coordinates-only check, with comments explaining why.

## Judgment calls

**Deviated from the literally-specified fix (b).** The task described fix (b) as "key the dedup
check on eventId ... instead of physical (topic, partition, offset)" and a `UNIQUE (event_id)`-style
constraint. I implemented that first, exactly as specified (`UNIQUE (event_id, dead_lettered)`,
`existsByEventIdAndDeadLettered`), and it failed immediately against real data: applying the Flyway
migration to the already-running stack's `scenario_service.events` table threw a genuine unique
violation —

```
Message    : ERROR: could not create unique index "events_event_id_dead_lettered_key"
  Detail: Key (event_id, dead_lettered)=(44f63ae9-614f-417d-94e7-93a39cf1de17, f) is duplicated.
```

Querying that row showed why: the same `eventId`, same topic/partition, at two different offsets
(58 and 60):

```
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c \
  "select event_id, dead_lettered, topic, \"partition\", \"offset\" from scenario_service.events where event_id='44f63ae9-614f-417d-94e7-93a39cf1de17';"
               event_id               | dead_lettered |     topic     | partition | offset
--------------------------------------+---------------+---------------+-----------+--------
 44f63ae9-614f-417d-94e7-93a39cf1de17 | f             | orders.events |         2 |     58
 44f63ae9-614f-417d-94e7-93a39cf1de17 | f             | orders.events |         2 |     60
(2 rows)
```

That is `docs/scenarios.md` Scenario 4 (Duplicate Event Delivery, a **frozen contract**) working as
designed: it deliberately republishes a record with the *same eventId* as a genuinely new Kafka
record, and the contract's "Observable proof" states "the timeline shows the event consumed twice
and the side effect applied once — which is the whole point, and is why the duplicate must be a real
republish rather than a UI label." An eventId-only (or `event_id, dead_lettered`) unique constraint
at the projection layer collides on exactly that legitimate case and would silently swallow the
second, contractually-required row.

Per the coordination protocol ("if a contract file is wrong... stop, don't work around it locally")
I checked whether `docs/scenarios.md` was actually wrong — it isn't; the requirement is coherent and
the literal fix (b) is what doesn't fit it. I did not edit `docs/scenarios.md`. Instead I implemented
a composite key, `UNIQUE (topic, "partition", "offset", event_id)`, which satisfies the actual
invariant the task's own rationale asked for ("correct regardless of whether offsets get reused")
without regressing Scenario 4:

- A broker reset produces a *new* record at a reused physical address but with a *different*
  `event_id` than whatever stale row occupies that address — the composite tuple no longer matches,
  so the row is (re)projected. This is the fix for issue #27.
- A genuine redelivery of the *same* physical record (rebalance replay, retry after a transient DB
  error) reproduces the identical tuple and is still correctly treated as a no-op — the property the
  original (topic, partition, offset) check existed for is preserved.
- Scenario 4's legitimate republish lands at a distinct offset from the original, so it's never
  deduped against it, regardless of sharing an eventId — Scenario 4 keeps working exactly as
  documented.

I verified this holds by checking `scenario_service.events` for the composite tuple before applying
the migration (no conflicts existed) and then live, below.

**Migration filename.** I renamed the file from the originally-planned
`V3__events_dedupe_by_event_id.sql` to `V3__events_dedupe_by_topic_partition_offset_and_event_id.sql`
to match what it actually does, once the design changed.

**Kafka volume mount path.** Used `/tmp/kraft-combined-logs`, confirmed by inspecting
`apache/kafka:4.0.0`'s `server.properties` (`log.dirs=/tmp/kraft-combined-logs`) rather than
assuming a path.

## How this was verified

All commands run against the already-running local `docker compose` stack (postgres, kafka, all
four domain services, scenario-service, frontend, grafana, prometheus — all up before this session
started).

**1. Reproduced the original bug** (temporarily reverted the fix via `git stash` on just the three
touched source files, rebuilt `scenario-service` with the old code against the old — no-volume —
`docker-compose.yml`, which was still the config the running `kafka` container had been started
with):

Created order A normally, confirmed 5 events projected:
```
$ curl -s -X POST http://localhost:8081/api/orders -d '{"customerId":"demo-customer","items":[{"sku":"SKU-001","quantity":1}]}'
{"id":"order-20162","status":"PENDING",...}
$ curl -s "http://localhost:8085/demo/events?aggregateId=order-20162"
... totalElements: 5 (OrderCreated offset 52, InventoryReserved offset 26, PaymentRequested offset 53, PaymentAuthorized offset 25, ShipmentCreated offset 24)
```

Recreated the `kafka` container (no volume ⇒ offsets reset to 0, confirmed in logs: `"Resetting
offset for partition orders.events-0 to position ... offset=0"` for both order-service's and
scenario-service's consumer groups). Created order B:

```
$ curl -s -X POST http://localhost:8081/api/orders -d '{"customerId":"demo-customer","items":[{"sku":"SKU-001","quantity":1}]}'
{"id":"order-20163","status":"PENDING",...}
$ curl -s "http://localhost:8085/demo/events?aggregateId=order-20163"
{"content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0}
```

Bug reproduced: order-20163's events were silently dropped. Confirmed the mechanism directly —
stale rows already occupying `orders.events` partition 0's low offsets from long before the reset:

```
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c \
  'select topic, "partition", "offset", aggregate_id, event_id from scenario_service.events where topic='"'"'orders.events'"'"' and "partition"=0 and "offset" < 5 order by "offset";'
     topic     | partition | offset | aggregate_id |               event_id
---------------+-----------+--------+--------------+--------------------------------------
 orders.events |         0 |      0 | order-20000  | 9c93d84e-f504-4be4-9feb-990301036361
 orders.events |         0 |      1 | order-20011  | 0bb6a85b-0f01-4e32-becd-b0248b067a56
 orders.events |         0 |      2 | order-20015  | d1dfb21f-025d-403d-a388-d147fa12ceb5
 orders.events |         0 |      3 | order-20016  | b232b24d-c40e-4b90-88b6-6807cf56822e
 orders.events |         0 |      4 | order-20023  | 902a02d5-bcc4-4247-a8e7-90e8683f8e7e
(5 rows)
```

**2. Restored the fix** (`git stash pop`), rebuilt `scenario-service` and let `kafka` recreate with
the new persistent-volume compose config. Flyway applied V3 successfully once the design was
corrected to the composite key:

```
"message":"Migrating schema \"scenario_service\" to version \"3 - events dedupe by topic partition offset and event id\""
"message":"Successfully applied 1 migration to schema \"scenario_service\", now at version v3 (execution time 00:00.032s)"
```

**3. Simulated a genuine broker reset despite the volume fix** (fix (b)'s defense-in-depth case —
topic deletion / fresh environment), by explicitly removing the new kafka volume and recreating the
container:

```
$ docker compose stop kafka && docker compose rm -f kafka
$ docker volume rm kafka-portfolio-project_orderfulfillment-kafka-data
$ docker compose up -d kafka
```

Created order D, landing at the exact same colliding low offsets as before (0, 0, 0, 0, 1):

```
$ curl -s -X POST http://localhost:8081/api/orders -d '{"customerId":"demo-customer","items":[{"sku":"SKU-001","quantity":1}]}'
{"id":"order-20165","status":"PENDING",...}
$ curl -s "http://localhost:8085/demo/events?aggregateId=order-20165"
totalElements: 5 — OrderCreated (orders.events, offset 0), InventoryReserved (inventory.events, offset 0),
PaymentRequested (orders.events, offset 1), PaymentAuthorized (payments.events, offset 0),
ShipmentCreated (fulfillment.events, offset 0)
```

Regression closed: same collision conditions as the reproduced bug, but now all 5 events are
projected and Order Detail's event timeline for order-20165 is intact.

**4. Re-verified the Duplicate Event Delivery scenario (docs/scenarios.md Scenario 4) still works,**
since that's the exact case the eventId-only design would have broken:

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/duplicate-event
{"id":"run-246","scenarioName":"duplicate-event","status":"RUNNING",...}
$ curl -s http://localhost:8085/demo/scenario-runs/run-246
{"status":"COMPLETED","orderId":"order-20166",...}   # normal fulfillment happened, no double side effect
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c \
  "select event_id, topic, \"partition\", \"offset\" from scenario_service.events where aggregate_id='order-20166' and event_type='OrderCreated';"
               event_id               |     topic     | partition | offset
--------------------------------------+---------------+-----------+--------
 2d5e7d70-d5e8-46b7-96fe-e7822da6ca7d | orders.events |         0 |      2
 2d5e7d70-d5e8-46b7-96fe-e7822da6ca7d | orders.events |         0 |      4
(2 rows)
```

Both the original and the republished duplicate (same `event_id`, different offset) were projected —
the composite-key dedupe does not swallow the legitimate duplicate.

**5. Full scenario-service test suite** (Testcontainers-backed, against a real Kafka+Postgres):

```
$ mvn -q -pl services/scenario-service -am test -Dtest='!HighVolumeScenarioIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
(exit 0, no failures reported)
```

`HighVolumeScenarioIntegrationTest.burstOfOrdersReachesFulfilledAndRecordsThroughputAndLag` was
excluded because it fails on this box independent of this change — confirmed by stashing this
fix's three source files back out and re-running just that test against the unmodified code:

```
$ mvn -q -pl services/scenario-service test -Dtest=HighVolumeScenarioIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0 ... ConditionTimeout
```

Same timeout failure with or without this fix — pre-existing flake (throughput/lag timing under
`await()`), unrelated to event-projection dedupe.

**6. Environment left as found:** all ten compose services are back up and healthy at the end of the
session (same set that was running before this task started); `kafka`'s only durable change is the
new persistent volume, which is part of the deliverable, not leftover test state.

```
$ docker compose ps --format "table {{.Name}}\t{{.Status}}"
NAME                                   STATUS
orderfulfillment-frontend              Up 3 hours
orderfulfillment-fulfillment-service   Up 4 hours (healthy)
orderfulfillment-grafana               Up 4 hours
orderfulfillment-inventory-service     Up 4 hours (healthy)
orderfulfillment-kafka                 Up 6 minutes (healthy)
orderfulfillment-order-service         Up 4 hours (healthy)
orderfulfillment-payment-service       Up 4 hours (healthy)
orderfulfillment-postgres              Up 4 hours (healthy)
orderfulfillment-prometheus            Up 4 hours
orderfulfillment-scenario-service      Up 7 minutes (healthy)
```

## Deliberately not covered

- **Duplicate-event scenario's timeline display race**, found during verification but out of scope:
  in step 4 above, the run's `timeline` array (`GET /demo/scenario-runs/{runId}`) never shows the
  second `OrderCreated` entry, even though both rows are correctly projected to
  `scenario_service.events` (confirmed via direct SQL). Root cause, confirmed by code inspection of
  `RunRegistry.finish()`
  (`services/scenario-service/src/main/java/com/orderfulfillment/scenario/runtime/RunRegistry.java:47-50`):
  the `correlationId -> runId` mapping is removed as soon as the scenario run completes, so any Kafka
  record consumed after that point (the async republish routinely lands after
  `orderStatusWatcher.awaitTerminal()` returns) can no longer find a run to append a timeline entry
  to. This is a pre-existing orchestration-timing gap in `DuplicateEventScenario`/`RunRegistry`,
  identical under the old dedupe key (the second row was always distinct by offset even before this
  fix) — not something this fix introduced or is scoped to touch. Flagging as a candidate follow-up
  ticket: either have `DuplicateEventScenario.run()` wait for the second projection before returning,
  or have `RunRegistry` retain the mapping briefly after completion.
- **Kubernetes/`kind` path** — not exercised; this is a docker-compose/application-level bug per the
  task's own scope, and `docker compose` exercises the same Kafka+Postgres+Spring code paths.
- **Frontend Order Detail page** — verified via the same `GET /demo/events` endpoint the frontend
  calls, not by loading the actual React page in a browser; this issue is backend-correctness themed
  and the fix is entirely in the persistence/dedupe layer, not the UI.
- **Multi-partition collision timing** — all verification used the default single-partition-per-topic
  local setup implied by the compose stack's topic auto-creation; did not construct a
  multi-partition scenario to check partition-specific offset bookkeeping (not expected to differ,
  since the fix keys on partition too, but not independently exercised).
