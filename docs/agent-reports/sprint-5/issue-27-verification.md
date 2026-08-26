# Issue #27 fix — independent verification

Verifies `docs/agent-reports/sprint-5/issue-27-event-projection-offset-reset-fix.md` against the
real system rather than trusting its claims. Result: the database-level fix (composite dedupe key)
holds under real reproduction; the docker-compose persistent-volume fix does not — it mounts the
named volume at a path Kafka never writes to, so a container recreation still resets every topic to
offset 0 exactly as before the fix.

## What changed

No source files were changed. This report itself, at
`docs/agent-reports/sprint-5/issue-27-verification.md`, is the only file created.

## How this was verified

### Claim 1 — docker-compose.yml persistent volume actually persists Kafka data across a stack rebuild

**FAIL.**

The compose file does add a named volume mounted at `/tmp/kraft-combined-logs`:

```
$ grep -A2 "orderfulfillment-kafka-data:" docker-compose.yml
      - orderfulfillment-kafka-data:/tmp/kraft-combined-logs
...
volumes:
  orderfulfillment-postgres-data:
  orderfulfillment-kafka-data:
```

But the running container's actual, effective `server.properties` has no `log.dirs` entry at all
(the compose file sets no `KAFKA_LOG_DIRS`/`KAFKA_LOG_DIR` env var), so Kafka falls back to its
built-in default, `/tmp/kafka-logs` — a different, unmounted directory:

```
$ docker exec orderfulfillment-kafka cat /opt/kafka/config/server.properties
advertised.listeners=INTERNAL://kafka:29092,HOST://localhost:9092
listeners=INTERNAL://0.0.0.0:29092,HOST://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
transaction.state.log.min.isr=1
controller.quorum.voters=1@localhost:9093
transaction.state.log.replication.factor=1
listener.security.protocol.map=CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,HOST:PLAINTEXT
inter.broker.listener.name=INTERNAL
controller.listener.names=CONTROLLER
offsets.topic.replication.factor=1
node.id=1
auto.create.topics.enable=true
group.initial.rebalance.delay.ms=0
process.roles=broker,controller
```

(no `log.dirs` line — confirmed absent from the file, not merely un-grepped)

```
$ docker exec orderfulfillment-kafka find / -maxdepth 2 -type d -newer /etc/hostname 2>/dev/null
...
/tmp/kafka-logs      <- where the actual partition log segments live
...
$ docker exec orderfulfillment-kafka find /tmp/kafka-logs -maxdepth 1 -iname "orders.events*"
/tmp/kafka-logs/orders.events-0
...
$ docker exec orderfulfillment-kafka ls -la /tmp/kraft-combined-logs   # the mounted volume path
total 8
drwxr-xr-x 2 root root 4096 Aug 25 22:40 .
drwxrwxrwt 1 root root 4096 Aug 25 23:55 ..
```

`/tmp/kraft-combined-logs` (the mount point) is empty; `/tmp/kafka-logs` (Kafka's real default,
unmounted, part of the container's ephemeral writable layer) has all the actual segment data. This
is the opposite of what the report's rationale claims ("confirmed by inspecting apache/kafka:4.0.0's
server.properties (log.dirs=/tmp/kraft-combined-logs)").

Reproduced the consequence directly — full `docker compose down` (no `-v`, volumes preserved) +
`docker compose up -d --build`, i.e. exactly the "stack rebuild" scenario issue #27 describes:

```
$ docker exec orderfulfillment-kafka kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic orders.events
Topic: orders.events  TopicId: 2BY-JcBPSjurxfL3cTUHLA  PartitionCount: 3 ...
# (before down/up, offsets 0-4 populated, 3 orders' worth of history)

$ docker compose down   # containers removed, named volumes NOT removed
$ docker compose up -d --build

$ docker exec orderfulfillment-kafka kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic orders.events
Topic: orders.events  TopicId: sie8ald9Rk-UaFd3RcWYtQ  PartitionCount: 3 ...
# new TopicId — a completely fresh topic, all prior data and offsets gone, despite the volume
```

The volume genuinely exists and survived the `down` (`docker volume ls` still shows
`kafka-portfolio-project_orderfulfillment-kafka-data`), but since Kafka never wrote to it, the
container recreation reset offsets to 0 exactly as issue #27 originally described. **Component 1 of
the fix does not work; every ordinary stack rebuild still resets Kafka.**

### Claim 2 — migration adds composite `UNIQUE(topic, partition, offset, event_id)`, not eventId-only

**PASS.**

```
$ cat services/scenario-service/src/main/resources/db/migration/V3__events_dedupe_by_topic_partition_offset_and_event_id.sql
...
ALTER TABLE events DROP CONSTRAINT events_topic_partition_offset_key;

ALTER TABLE events ADD CONSTRAINT events_topic_partition_offset_event_id_key
    UNIQUE (topic, "partition", "offset", event_id);
```

Confirmed live in the running database (migration actually applied, not just present on disk):

```
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c \
  "select version, description, success from scenario_service.flyway_schema_history order by installed_rank;"
 version |                     description                      | success
---------+------------------------------------------------------+---------
         | << Flyway Schema Creation >>                         | t
 1       | scenario runs                                        | t
 2       | events                                               | t
 3       | events dedupe by topic partition offset and event id | t

$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c '\d scenario_service.events'
...
Indexes:
    "events_topic_partition_offset_event_id_key" UNIQUE CONSTRAINT, btree (topic, partition, "offset", event_id)
```

Genuinely the composite key, not `UNIQUE(event_id)` alone.

### Claim 3 — EventProjectionConsumer/repository updated to match the new dedupe key

**PASS.**

`EventRecordRepository.existsByTopicAndPartitionAndOffsetAndEventId(...)` exists and is what
`EventProjectionConsumer.project()` calls (verified by reading both files directly — see
`services/scenario-service/src/main/java/com/orderfulfillment/scenario/domain/EventRecordRepository.java:17`
and
`services/scenario-service/src/main/java/com/orderfulfillment/scenario/projection/EventProjectionConsumer.java:123-124`).
No leftover reference to the old `existsByTopicAndPartitionAndOffset` in either file.

### Behavioral test — full order to FULFILLED, events project via GET /demo/events

**PASS**, on a clean stack (after the down/up above cleared the wedged producer-epoch state a
several-hours-old, previously-chaos-tested Kafka session had accumulated — see Judgment calls).

```
$ curl -s -X POST http://localhost:8081/api/orders -d '{"customerId":"clean-test-1","items":[{"sku":"SKU-001","quantity":1}]}'
{"id":"order-20169","status":"PENDING",...}

$ curl -s http://localhost:8081/api/orders/order-20169
{"id":"order-20169",...,"status":"FULFILLED",...}

$ curl -s "http://localhost:8085/demo/events?aggregateId=order-20169"
{"content":[...5 events: OrderCreated, InventoryReserved, PaymentRequested, PaymentAuthorized,
ShipmentCreated...],"totalElements":5,...}
```

### Behavioral test — force a Kafka reset, run a second order at colliding offsets, confirm it still projects

**PASS.** Since component 1 doesn't actually prevent resets, this reset happens for free on every
container recreation; forced one explicitly to control the timing:

```
$ docker compose stop kafka && docker compose rm -f kafka && docker compose up -d kafka
$ docker compose restart order-service inventory-service payment-service fulfillment-service scenario-service
$ curl -s -X POST http://localhost:8081/api/orders -d '{"customerId":"collision-test","items":[{"sku":"SKU-001","quantity":1}]}'
{"id":"order-20170",...}
$ curl -s http://localhost:8081/api/orders/order-20170
{"id":"order-20170",...,"status":"FULFILLED",...}
$ curl -s "http://localhost:8085/demo/events?aggregateId=order-20170"
{"content":[...all 5 events present...],"totalElements":5,...}
```

Directly confirmed a genuine physical-coordinate collision occurred and was handled correctly —
three unrelated orders (`order-20002` from days earlier, `order-20164` from earlier this session, and
`order-20170` just created) each have a distinct row at the exact same `(topic, partition, offset)`
address, differentiated only by `event_id`:

```
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c \
  'select topic, "partition", "offset", aggregate_id, event_id, recorded_at from scenario_service.events
   where topic='"'"'orders.events'"'"' and "partition"=1 and "offset" in (0,1) order by recorded_at;'
     topic     | partition | offset | aggregate_id |               event_id
---------------+-----------+--------+--------------+--------------------------------------
 orders.events |         1 |      0 | order-20002  | 0d5ab556-8e13-4a56-b527-bef4bc7efe2a
 orders.events |         1 |      1 | order-20002  | 68124026-8bfc-49c9-b310-7527afd2a2fd
 orders.events |         1 |      0 | order-20164  | 96b87454-ecf4-486d-8a13-04a6849e4071
 orders.events |         1 |      0 | order-20170  | e8bbb499-6e5c-4848-8828-e45467cb1a7a
 orders.events |         1 |      1 | order-20170  | 6d250d33-aec6-4b8e-a08f-b83d1f6aea89
```

Under the old (topic, partition, offset)-only key, rows 3 and 4 would have collided with row 1 and
been silently dropped. They weren't. This is direct proof the composite-key fix does what it claims,
independent of whether the volume mount (component 1) works.

### Behavioral test — Duplicate Event Delivery scenario (docs/scenarios.md Scenario 4) still shows both rows

**PASS.**

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/duplicate-event
{"id":"run-247","scenarioName":"duplicate-event","status":"RUNNING",...}
$ curl -s http://localhost:8085/demo/scenario-runs/run-247
{"status":"COMPLETED","orderId":"order-20171",...}

$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c \
  "select event_id, event_type, topic, \"partition\", \"offset\" from scenario_service.events where aggregate_id='order-20171' order by \"offset\";"
               event_id               |         event_type         |      topic       | partition | offset
--------------------------------------+----------------------------+------------------+-----------+--------
 6ccd2c3a-e9cd-4434-a11a-d6c4e069bb79 | OrderCreated               | orders.events    |         2 |      0
 08c71c0c-31be-4904-9ce0-8dc9b3e1ec1c | InventoryReservationFailed | inventory.events |         2 |      0
 6ccd2c3a-e9cd-4434-a11a-d6c4e069bb79 | OrderCreated               | orders.events    |         2 |      1
```

Both `OrderCreated` rows (same `event_id`, different offset) persisted — the composite key does not
regress the frozen contract. The run's own timeline (`GET /demo/scenario-runs/run-247`) also showed
both `OrderCreated` entries this time, unlike the original report's step 4 — likely timing-dependent
(the report itself flags this as a known, separate, pre-existing race under "Deliberately not
covered"; not re-litigated here).

### Test suite

**PASS**, with the same pre-existing-flake caveat the original report raised, plus one more flake I
found independently:

```
$ mvn -q -pl services/common,services/scenario-service -am test \
    -Dtest='!HighVolumeScenarioIntegrationTest,!IdleResetSchedulerIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
(exit 0)
```

`HighVolumeScenarioIntegrationTest` — excluded per the original report's own finding (pre-existing
timing flake, unrelated to this change).

`IdleResetSchedulerIntegrationTest.resumesConsumerAndClearsPaymentOverrideAfterIdlePeriodButNotWhileARunIsInProgress`
— found failing in my first full-suite run (not mentioned in the original report), re-ran it alone
twice and got two *different* assertion failures (once at line 96, once at line 86), confirming it's
a non-deterministic timing flake (short real-clock sleeps racing a scheduled poll), not a
deterministic regression:

```
$ mvn -q -pl services/scenario-service test -Dtest=IdleResetSchedulerIntegrationTest ...
[ERROR] IdleResetSchedulerIntegrationTest...:86 Expected exactly 0 requests matching ... but received 1
```

Confirmed unrelated to this fix — the test file has no commits since before this sprint and wasn't
touched by the fix commit:

```
$ git log --oneline -1 -- services/scenario-service/src/test/java/com/orderfulfillment/scenario/IdleResetSchedulerIntegrationTest.java
1a81745 fix inventory reset not clearing reservations, wedging the live demo
$ git show <fix-commit> --stat | grep -i IdleReset
(no output)
```

### Environment left as found

```
$ docker compose ps --format "table {{.Name}}\t{{.Status}}"
NAME                                   STATUS
orderfulfillment-frontend              Up 8 minutes
orderfulfillment-fulfillment-service   Up 6 minutes (healthy)
orderfulfillment-grafana               Up 8 minutes
orderfulfillment-inventory-service     Up 6 minutes (healthy)
orderfulfillment-kafka                 Up 6 minutes (healthy)
orderfulfillment-order-service         Up 6 minutes (healthy)
orderfulfillment-payment-service       Up 6 minutes (healthy)
orderfulfillment-postgres              Up 9 minutes (healthy)
orderfulfillment-prometheus            Up 8 minutes
orderfulfillment-scenario-service      Up 6 minutes (healthy)
```

Same ten services running as when verification started; no `docker compose down` left the stack
torn down. Test data created during this session (`order-20164` through `order-20171`, plus a
handful of scenario runs) was left in place, same as the original report's own test orders
(`order-20162` through `order-20166`) — cleaning up demo data was out of scope for either session.

## Judgment calls

- **Restarted `order-service` mid-session, then later did a full `docker compose down`/`up` and
  recreated the `kafka` container again.** The stack I inherited had already been used for several
  hours of manual chaos testing by the report's own author (repeated kafka container/volume
  recreation without always restarting dependent producers). My first attempt at "run an order to
  FULFILLED" hit a genuinely lost event — an outbox row marked `PUBLISHED` with no corresponding
  Kafka record at all, likely a stale idempotent-producer-epoch artifact from that history, not a
  consequence of the fix under test. Rather than report that as the fix's failure, I reset to a clean
  baseline (`down`/`up`, preserving named volumes) and reproduced from there — this is what actually
  surfaced the real, deterministic bug (the volume-mount path mismatch), which a dirtier repro would
  have muddied.
- **Did not attempt to fix the discovered `log.dirs` mismatch or file a proposed contract change for
  it.** It isn't a `docs/openapi|events|db-ownership|order-state-machine` contract file, so the
  coordination protocol doesn't apply, and the task explicitly scoped this session to verification,
  not remediation.
- **Treated `IdleResetSchedulerIntegrationTest`'s failure as a pre-existing flake rather than a
  regression**, based on: (a) it touches scheduler/WireMock timing entirely unrelated to
  `EventProjectionConsumer`/`EventRecordRepository`, (b) it reproduces with different failure points
  across runs (non-determinism, not a stable break), and (c) the file has no changes in or after the
  fix commit.

## Deliberately not covered

- **Root-causing why the persistent volume mounts to the wrong path**, beyond identifying that it
  does. Candidates worth checking in a follow-up: whether `apache/kafka:4.0.0`'s docker-entrypoint
  actually derives `log.dirs` from `KAFKA_LOG_DIRS`/`KAFKA_LOG_DIR"` (unset here) rather than a
  literal default matching `/tmp/kraft-combined-logs`, or whether an older/different image tag uses
  that path and 4.0.0 changed it. Not chased further — out of scope for a verification pass that
  doesn't fix source.
- **Kubernetes/`kind` path** — not exercised, same as the original report; this bug and its fix are
  docker-compose/application-level.
- **Frontend Order Detail page in a browser** — verified via the same `GET /demo/events` endpoint the
  frontend calls, not by loading the React page.
- **Multi-partition-specific offset bookkeeping beyond what naturally occurred** — the partition
  count for `orders.events` grew from 1 to 3 partitions across container/service restarts during this
  session (a `NewTopic` bean apparently requesting more partitions than the topic's original
  auto-create default); did not investigate why or whether that's intentional, since it didn't affect
  either the persistence-fix or dedupe-fix conclusions and is orthogonal to issue #27.
- **The stray `HttpClientErrorException$NotFound` WARN** logged by `ScenarioRunExecutor` for an
  unrelated `run-109` during the full-suite Maven run — appears to be an artifact of a different,
  otherwise-passing test's own WireMock lifecycle (build exited 0, no failure attributed to that run
  in the surefire summary); not chased further since it didn't correspond to a reported test failure.
