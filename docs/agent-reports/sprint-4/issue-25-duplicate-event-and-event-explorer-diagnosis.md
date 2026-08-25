# Issue #25 — duplicate-event scenario and Order Detail Events section: diagnosis

Diagnosis only, per the delegation scope. No backend code was modified. This report covers the full
8-scenario validation pass plus the two specifically-flagged symptoms, which turn out to share a
single root cause.

## What changed

Nothing. This was a read-only diagnosis session against the live `docker compose` stack (already
running, not restarted, not torn down). No source files were created or modified.

## Root cause (single defect, explains both reported symptoms)

**`EventProjectionConsumer`'s idempotency key is not stable across a Kafka broker reset, and the
Kafka service in `docker-compose.yml` has no persistent volume while `postgres` does.**

- `docker-compose.yml`'s `kafka:` service (`apache/kafka:4.0.0`) declares no `volumes:` entry at all.
  `postgres:` does (`kafka-portfolio-project_orderfulfillment-postgres-data`, confirmed via
  `docker volume ls`). So every `docker compose down && docker compose up` — including the rebuild
  already done earlier this session after the OOM kill — wipes every Kafka topic's data and every
  consumer group's committed offsets back to nothing, while Postgres (and everything projected into
  it) survives untouched.
- `EventProjectionConsumer.project()` (`services/scenario-service/src/main/java/com/orderfulfillment/scenario/projection/EventProjectionConsumer.java:112`)
  dedupes purely on the record's physical Kafka coordinates:
  ```java
  if (eventRecordRepository.existsByTopicAndPartitionAndOffset(record.topic(), record.partition(), record.offset())) {
      log.debug("Already projected {}-{}@{}, skipping redelivery", record.topic(), record.partition(), record.offset());
      return;
  }
  ```
  `(topic, partition, offset)` only uniquely identifies a record within one continuous broker log. It
  is not a stable identifier across a broker that has been wiped and started producing from offset 0
  again. After a reset, the *first* new message written to, say, `orders.events` partition 0 gets
  offset 0 again — the same coordinates already occupied by some pre-reset row still sitting in
  `scenario_service.events` (which Postgres never lost). `existsByTopicAndPartitionAndOffset` matches
  that stale row and the genuinely new event is silently treated as an already-seen redelivery and
  dropped. The skip is logged only at `log.debug`, and `application.yml` sets
  `logging.level.com.orderfulfillment: INFO`, so this produces **zero log output** — nothing to
  grep for, no error, no warning.
- Because the whole `project()` method returns normally either way (this is by design — the whole
  point of the check is "safe no-op on redelivery"), the Kafka consumer never throws, so the listener
  container commits the offset normally. This is why the `scenario-service-projection` consumer group
  shows **zero lag** and a **Stable** state throughout — it is processing every record exactly once,
  correctly by its own logic, it's just concluding "already have this one" for records it has never
  actually seen before.
- The effect is per-`(topic, partition)` and self-healing: once a partition's post-reset offsets climb
  past whatever the highest offset was in that `(topic, partition)` before the reset, new records stop
  colliding and start being projected normally again. Partitions heal independently and at different
  times depending on how much traffic each one carried before the reset vs. how much it's carried
  since. I captured this directly, mid-investigation (see Verified: partition healing below).

### Why this is not the "stuck/never-initialized consumer group" lead

A concurrent investigation on issue #26 surfaced scenario-service logs showing `scenario-service-projection`
reporting "Found no committed offset" for multiple partitions, and speculated this was a stuck/lagging
consumer group. I checked this directly and can rule it out:

```
$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --describe --group scenario-service-projection --state
GROUP                       COORDINATOR (ID)     ASSIGNMENT-STRATEGY  STATE     #MEMBERS
scenario-service-projection localhost:9092  (1)  range                Stable    2
```

All "no committed offset" lines in the container's entire log history carry the same timestamp window
(19:09:37.xxx–19:09:38.xxx), which is the container's own startup (`Started ScenarioServiceApplication`
logged at 19:09:37.633). That is the single, expected, one-time event of a consumer group joining for
the first time after Kafka lost all committed offsets in the reset — not an ongoing or recurring
condition. No rejoin/rebalance events appear anywhere in the logs after that initial join, and the
group has stayed `Stable` with both members holding full partition assignments (12 partitions each)
the entire time I've been testing. The consumer is demonstrably *not* stuck: I watched its committed
offsets advance in lockstep with newly-produced messages, with lag staying at 0 throughout (evidence
below). It's processing every record; it's just wrongly concluding most of them are duplicates.

## How this was verified

### 1. Reproduced the empty-events symptom on a brand-new order

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order
{"id":"run-232", ... }
$ curl -s http://localhost:8085/demo/scenario-runs/run-232   # polled to COMPLETED, orderId=order-20087, status FULFILLED
$ curl -s "http://localhost:8085/demo/events?aggregateId=order-20087"
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment \
    -c "select event_type, aggregate_id, topic, occurred_at from scenario_service.events where aggregate_id='order-20087';"
 event_type | aggregate_id | topic | occurred_at
------------+--------------+-------+-------------
(0 rows)
```
Confirmed empty at the query layer *and* at the table itself — this rules out a query/filter bug in
`GET /demo/events` (`EventQueryService`/`EventRecordRepository.search`), which I read and found
structurally correct (`docs/scenarios.md`'s honesty-boundary fields all map straight through). The
defect is upstream, in the projection, not the read path.

### 2. Confirmed the record really was published to Kafka (rules out "order-service never emitted the event")

```
$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic orders.events --partition 0 --from-beginning --timeout-ms 4000 \
    --property print.key=true --property print.partition=true --property print.offset=true
Partition:0 Offset:0 order-20087 {"eventId":"755f821c-...","eventType":"OrderCreated", ...}
Partition:0 Offset:1 order-20087 {"eventId":"e904b6a3-...","eventType":"PaymentRequested", ...}
Processed a total of 2 messages
```

### 3. Confirmed the exact same coordinates were already occupied by a stale, pre-reset row

```
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment \
    -c "select event_id, event_type, aggregate_id, occurred_at from scenario_service.events where topic='orders.events' and partition=0 and \"offset\" in (0,1);"
               event_id               |  event_type  | aggregate_id |          occurred_at
--------------------------------------+--------------+--------------+-------------------------------
 9c93d84e-f504-4be4-9feb-990301036361 | OrderCreated | order-20000  | 2026-08-21 07:26:48.032557+00
 0bb6a85b-0f01-4e32-becd-b0248b067a56 | OrderCreated | order-20011  | 2026-08-21 07:27:46.978975+00
```
`order-20087`'s real, brand-new `OrderCreated` at `(orders.events, partition 0, offset 0)` collides
exactly with `order-20000`'s stale row at those same coordinates from 2026-08-21 (four days before
this session — this table has been stale since well before today's OOM incident, meaning this defect
has been recurring across *every* stack rebuild since at least then, not just today's).

### 4. Positive control: a topic/partition with no collision projects correctly

`poison-message`'s DLQ record landed at `(inventory.dlq, partition 0, offset 0)` — the one pre-existing
`inventory.dlq` row was at partition **2**, offset 0, so no collision:
```
$ curl -s -X POST http://localhost:8085/demo/scenarios/poison-message   # run-241, COMPLETED
timeline: [{"kind":"EVENT","label":"OrderCreated","detail":{"topic":"inventory.dlq","partition":0,"offset":0,"deadLettered":true,...}}]
$ docker exec orderfulfillment-postgres psql ... "select ... from scenario_service.events where topic='inventory.dlq' order by \"offset\";"
 partition=2, offset=0  (stale, 2026-08-21)
 partition=0, offset=0  (order-20159... run's poison record, 2026-08-25 — successfully written)
```
Same consumer, same code path, no collision → normal projection, timeline `EVENT` entry present, row
in the table. This isolates the defect to the collision itself, not to the consumer being broken.

### 5. Verified partition-level self-healing live, and reproduced duplicate-event both failing and succeeding

```
$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-consumer-groups.sh --describe --group scenario-service-projection | grep orders.events
orders.events  1  40  40  0   # current offset 40, historical DB max for partition 1 was 43 — still colliding
orders.events  0  49  49  0   # historical max was 34 — now past it, healed
orders.events  2  58  58  0   # historical max was 56 — now past it, healed
```
Earlier `duplicate-event` run (`run-231`, 19:24:05) failed:
```
"Scenario run run-231 (duplicate-event) failed"
java.lang.IllegalStateException: OrderCreated for order-20086 was not observed by the event projection in time
    at DuplicateEventScenario.awaitProjectedOrderCreated(DuplicateEventScenario.java:59)
```
A later run (`run-244`, after partition 2 had healed) landed on partition 2 and **succeeded end to
end**, including the republish and the correct single-application-of-side-effect proof the scenario
exists to demonstrate:
```
$ curl -s -X POST http://localhost:8085/demo/scenarios/duplicate-event   # run-244
"status":"COMPLETED", "orderId":"order-20159"
timeline:
  EVENT OrderCreated       topic=orders.events partition=2 offset=58
  EVENT InventoryReserved  topic=inventory.events partition=2 offset=32
  EVENT PaymentRequested   topic=orders.events partition=2 offset=59
  EVENT OrderCreated       topic=orders.events partition=2 offset=60   <- the republished duplicate
  STATE_CHANGE Order FULFILLED
```
`DuplicateEventScenario`'s own logic (`services/scenario-service/src/main/java/com/orderfulfillment/scenario/scenarios/DuplicateEventScenario.java`)
is correct — it polls the projection for the `OrderCreated` row by correlation id
(`awaitProjectedOrderCreated`, lines 47–61) and republishes it verbatim once found
(`republish`, lines 63–70). It fails only because it depends on the same broken
`EventProjectionConsumer` the Order Detail page depends on. Order Service and Inventory Service's own
idempotent-consumer behavior (the actual thing Scenario 4 is supposed to demonstrate) is intact and
correctly proven by run-244's timeline — one `InventoryReserved`, not two, despite two `OrderCreated`
deliveries.

### Full 8-scenario pass

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | `standard-order` | **Working** | run-232, COMPLETED, `order-20087` reached `FULFILLED`, HTTP 201 timeline entry precedes STATE_CHANGE entries as documented. |
| 2 | `out-of-stock` | **Working** | run-238, COMPLETED, `order-20094` → `REJECTED_OUT_OF_STOCK`. |
| 3 | `payment-failure` | **Working** | run-239, COMPLETED, `order-20095` → `PAYMENT_FAILED`; verified SKU-001 inventory returned to seed level: `curl .../api/inventory/SKU-001` → `availableQuantity:10, reservedQuantity:0`. |
| 4 | `duplicate-event` | **Broken (intermittently, per-partition)** | See root cause above. Fails whenever the order's key hashes to a still-colliding partition (run-231); succeeds once that partition has healed past its pre-reset offset ceiling (run-244). Scenario's own code is correct. |
| 5 | `consumer-outage` | **Working** | run-240, COMPLETED in 5.25s (pause ~4s as configured), `order-20096` reached `FULFILLED` after listener resume; pause/resume HTTP calls visible in timeline. |
| 6 | `poison-message` | **Working** | run-241, COMPLETED, record routed to `inventory.dlq` with `deadLettered:true`. (Note: this scenario's timeline entry did get projected — see positive control above — because it happened to land on a non-colliding partition; that's incidental to this scenario's own correctness, not evidence against the root cause.) |
| 7 | `inventory-contention` | **Working** | run-242, COMPLETED, `order-20097` → `REJECTED_OUT_OF_STOCK`, `order-20098` → `FULFILLED`; verified `SKU-004` invariant: `reservedQuantity(2) <= availableQuantity(2)`, never oversold. |
| 8 | `high-volume` | **Working** | run-243, COMPLETED in 6.75s, 60 orders, 272 timeline entries including a "Consumer lag observed" EVENT entry and a "High-volume batch summary" STATE_CHANGE, satisfying the "throughput/lag observable" success condition. |

## Judgment calls

- I did not modify `EventProjectionConsumer`, the dedup key, or `docker-compose.yml`'s Kafka volume
  configuration, per explicit instruction — I only describe the mechanism and evidence here. The two
  candidate fixes I'd flag for whoever picks this up (not evaluated in depth, no code touched): (a)
  give the `kafka` service a persistent volume in `docker-compose.yml` so this class of collision
  can't recur locally (matches how `postgres` is already configured), and/or (b) key the dedup check on
  something stable across broker resets — `eventId` is already captured on every row and is exactly
  the kind of stable identifier idempotency should be keyed on; `(topic, partition, offset)` is used
  today specifically to make *physical redelivery* (a genuine at-least-once Kafka redelivery within one
  broker's lifetime) a safe no-op, per the class's own Javadoc rationale — but nothing in that Javadoc
  addresses invalidation of the key across a broker reset. Both are real, independent gaps and either
  alone would fix today's symptom; I'd defer to whoever owns this on which (or both) to take, since (a)
  is an infra decision and (b) is a schema/semantics decision on `scenario_service.events`' unique
  constraint (currently `UNIQUE (topic, partition, offset)` per `V2__events.sql`).
- I ran the full 8-scenario pass using the shared, already-running stack rather than a fresh one, since
  tearing down and rebuilding would have (a) disrupted the developer's live review, explicitly
  disallowed, and (b) reset Kafka again, adding a fresh round of stale-collision noise on top of what I
  was trying to characterize. This means my scenario-pass results reflect the *current*, partially
  self-healed state of the events table, not a clean-room state — I called this out explicitly wherever
  it mattered (poison-message's DLQ entry, duplicate-event's partition-2 success).
  Re-running the full pass again right now would likely show `duplicate-event` succeeding more often
  than it did earlier in this session, purely because more partitions have healed since — that's
  expected given the mechanism, not evidence the defect is gone.
- I treated the `reserved_quantity` on `SKU-004`/`SKU-003` staying at its post-reservation value
  (`2`/`60`) rather than returning to `0` after the corresponding orders reached `FULFILLED` as an
  observation worth flagging but did not chase it — it wasn't part of the assigned scope, and the
  specific invariant Scenario 7 documents (`reserved_quantity <= available_quantity`, backed by
  `docs/db-ownership.md`'s CHECK constraint) held in every case I checked. I did not determine whether
  reserved quantity is expected to zero out on fulfillment or represents something else in the intended
  design.

## Deliberately not covered

- **No code fix was written or proposed as a diff** — this was diagnosis-only per explicit instruction.
- **`reserved_quantity` not decrementing after `FULFILLED`** (observed on SKU-004 after
  `inventory-contention` and SKU-003 after `high-volume`) — flagged above as a judgment call, not
  root-caused. Worth a separate, focused look at Inventory Service's fulfillment-consumption path
  (`ShipmentCreated`/fulfillment-side inventory consumer) to determine if this is expected or a second,
  unrelated defect.
- **Whether `(topic, partition, offset)` collisions could also affect a *real* domain consumer's
  `processed_events` idempotency ledger** (Order/Inventory/Payment services' own dedup, not just
  Scenario Service's projection) — I did not check whether those services key their own idempotency on
  `eventId` (which would make them immune to this specific collision) or on similarly Kafka-physical
  coordinates. If any of them use physical coordinates the same way, the *actual* domain-logic
  idempotency guarantee (not just the Event Explorer's read-only projection) could be compromised by a
  broker reset — this would be a materially more serious finding and deserves a dedicated look. I did
  not have evidence either way by the end of this session.
- **Did not verify the DLQ error/retry-count UI surface** for `poison-message` beyond confirming the
  record's `deadLettered:true` projection — `docs/scenarios.md`'s "error inspectable and retry count
  shown" success condition implies a UI or endpoint I didn't check (the projection intentionally never
  records retry count per `EventProjectionConsumer`'s own "honesty boundary" Javadoc, so if the UI
  claims to show one, that's worth checking separately — I didn't look at the frontend DLQ view at all).
- **Did not test what happens if `docker compose down`/`up` is repeated multiple times in a row** to
  see whether the collision window compounds or resets cleanly each time — inferred from the mechanism
  but not directly observed across a second reset (would have required tearing down the shared stack,
  disallowed).
- Left the shared `docker compose` stack exactly as found — running, healthy, not torn down. I did
  create additional orders/scenario runs (`run-232` through `run-244`) and ran `POST /demo/reset` twice
  during testing, which is within the intended use of that endpoint but does mean the developer's
  review environment now has additional order/run history beyond what was there when I started.
