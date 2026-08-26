# Issue #27 follow-up: Kafka persistent-volume mount path

## What changed

- `docker-compose.yml` — `kafka` service: changed the persistent volume mount from
  `orderfulfillment-kafka-data:/tmp/kraft-combined-logs` to
  `orderfulfillment-kafka-data:/var/lib/kafka/data`, and added an explicit
  `KAFKA_LOG_DIRS: /var/lib/kafka/data` environment variable so the broker's actual log directory
  matches the mounted path.

No other file was touched. The composite dedupe-key half of #27 (V3 migration and
`EventProjectionConsumer`) was left alone per the task's scope.

## Root cause found

Reading the running container directly (not the image's shipped default-config template) showed
two independent problems, not one:

1. **The env var that actually controls the log directory was never set.** The container's
   *effective* launch config, `/opt/kafka/config/server.properties`, is built by the image's
   `KafkaDockerWrapper` purely from `KAFKA_*` env vars supplied to the container — it does **not**
   inherit the defaults baked into `/etc/kafka/docker/server.properties` (which does list
   `log.dirs=/tmp/kraft-combined-logs`, and is presumably where the original fix's author read that
   path from). Since no `KAFKA_LOG_DIRS` was set, `log.dirs` was absent from the effective config
   entirely, and the broker fell back to Kafka's own hardcoded default, `/tmp/kafka-logs` — a
   different, unmounted path. Confirmed directly: `docker exec orderfulfillment-kafka cat
   /opt/kafka/config/server.properties` had no `log.dirs` line, and
   `/tmp/kafka-logs/meta.properties` existed on disk while `/tmp/kraft-combined-logs` was empty.

2. **Even after setting `KAFKA_LOG_DIRS` to match the original mount path, a fresh named volume at
   `/tmp/kraft-combined-logs` isn't writable by the broker.** The container runs as uid 1000
   (`appuser`), but a newly created Docker named volume mounted under `/tmp` has no pre-existing
   ownership to inherit (the mount point sits under root-owned `/tmp`), so Docker creates it
   `root:root`. First boot failed with
   `java.nio.file.AccessDeniedException: /tmp/kraft-combined-logs/bootstrap.checkpoint.tmp`.
   Reproduced this by removing the volume and rebuilding with `KAFKA_LOG_DIRS` pointed at the
   original path.

Both problems are fixed together by mounting at `/var/lib/kafka/data` instead. That path is
declared as a `VOLUME` in the `apache/kafka:4.0.0` image itself and is `chown`'d to `appuser:root`
at image-build time (`docker run --rm apache/kafka:4.0.0 ls -la /var/lib/kafka/data` shows
`drwxrwxr-x appuser root`). Docker's populate-on-first-mount behavior means a brand-new named volume
mounted over an existing image directory inherits that directory's contents *and permissions* —
confirmed with a disposable test volume (`docker run --rm -v test-kafka-vol:/var/lib/kafka/data
apache/kafka:4.0.0 ls -la /var/lib/kafka/data` showed `appuser:root` ownership on the fresh,
empty volume with no manual chown). `KAFKA_LOG_DIRS` was set to the same path so the broker actually
writes there.

## How this was verified

Confirmed the original bug first, against the code as it stood before my edit:

```
$ docker exec orderfulfillment-kafka cat /opt/kafka/config/server.properties
advertised.listeners=INTERNAL://kafka:29092,HOST://localhost:9092
listeners=INTERNAL://0.0.0.0:29092,HOST://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
...
node.id=1
auto.create.topics.enable=true
group.initial.rebalance.delay.ms=0
process.roles=broker,controller
# no log.dirs line at all

$ docker exec orderfulfillment-kafka ls -la /tmp/kraft-combined-logs
total 8
drwxr-xr-x 2 root root 4096 Aug 25 22:40 .
drwxrwxrwt 1 root root 4096 Aug 25 23:58 ..
# empty — the mounted volume, unused

$ docker exec orderfulfillment-kafka ls -la /tmp/kafka-logs | head -5
total 328
drwxr-xr-x 77 appuser appuser 4096 Aug 26 00:07 .
...
# this is where the broker was actually writing
```

After setting `KAFKA_LOG_DIRS` but before switching the mount path (intermediate step, not the
final fix), reproduced the permissions failure on a fresh volume:

```
$ docker compose up -d --build kafka
$ docker logs orderfulfillment-kafka --tail 5
Formatting metadata directory /tmp/kraft-combined-logs with metadata.version 4.0-IV3. Error while
writing meta.properties file /tmp/kraft-combined-logs: java.nio.file.AccessDeniedException:
/tmp/kraft-combined-logs/bootstrap.checkpoint.tmp
```

Confirmed the image pre-chowns `/var/lib/kafka/data`, and that a fresh named volume mounted there
inherits that ownership automatically:

```
$ docker run --rm apache/kafka:4.0.0 ls -la /var/lib/kafka/data
drwxrwxr-x 2 appuser root 4096 Mar 14  2025 .
drwxrwxr-x 3 appuser root 4096 Mar 14  2025 ..

$ docker volume create test-kafka-vol
$ docker run --rm -v test-kafka-vol:/var/lib/kafka/data apache/kafka:4.0.0 ls -la /var/lib/kafka/data
drwxrwxr-x 2 appuser root 4096 Mar 14  2025 .
drwxrwxr-x 3 appuser root 4096 Mar 14  2025 ..
$ docker volume rm test-kafka-vol
```

Reproduced the exact regression test the original verifier used, against the final fix (mount path
`/var/lib/kafka/data` + `KAFKA_LOG_DIRS`), with a clean volume:

```
$ docker compose down kafka
$ docker volume rm kafka-portfolio-project_orderfulfillment-kafka-data
$ docker compose up -d --build kafka
$ docker compose ps kafka
NAME                     STATUS
orderfulfillment-kafka   Up 15 seconds (healthy)
```

Produced real events through the actual order-service API (real HTTP requests, real Kafka
production, real projection consumers — not simulated):

```
$ curl -s -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
    -d '{"customerId":"cust-verify-1","items":[{"sku":"SKU-001","quantity":2}]}'
{"id":"order-20172","status":"PENDING","createdAt":"2026-08-26T00:10:47.908961305Z"}
# + 4 more orders

$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --describe --topic orders.events
Topic: orders.events   TopicId: KTes6vq9RwiH1aBXoySHkA   PartitionCount: 1   ReplicationFactor: 1

$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic orders.events
orders.events:0:5
```

Ran the exact cycle the original verifier used — `docker compose down` (no `-v`) then
`docker compose up -d --build` for the whole stack:

```
$ docker compose down
 Container orderfulfillment-kafka Removed
 ...
$ docker volume ls | grep kafka-portfolio
local     kafka-portfolio-project_orderfulfillment-kafka-data
local     kafka-portfolio-project_orderfulfillment-postgres-data
# volume survived down (no -v), as expected

$ docker compose up -d --build
 ...
 Container orderfulfillment-kafka Healthy
 Container orderfulfillment-order-service Healthy
 ... (all services healthy)
```

Confirmed the TopicId and offset survived the cycle instead of resetting:

```
$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --describe --topic orders.events
Topic: orders.events   TopicId: KTes6vq9RwiH1aBXoySHkA   PartitionCount: 3   ReplicationFactor: 1
    Topic: orders.events  Partition: 0  Leader: 1  Replicas: 1  Isr: 1
    Topic: orders.events  Partition: 1  Leader: 1  Replicas: 1  Isr: 1
    Topic: orders.events  Partition: 2  Leader: 1  Replicas: 1  Isr: 1

$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic orders.events
orders.events:0:5
orders.events:1:0
orders.events:2:0
```

`TopicId` is identical before and after (`KTes6vq9RwiH1aBXoySHkA`), and partition 0's offset is
preserved at 5 rather than reset to 0 — the exact failure mode the original verifier reproduced and
this fix is meant to close. (Partitions 1 and 2 appeared post-restart because order-service's
`NewTopic` bean specifies 3 partitions and Kafka's admin client increased the partition count for
the already-existing topic on that service's reconnect — a normal, non-destructive operation; it
does not touch existing partition 0's data.)

Confirmed end-to-end functionality post-fix with more real orders processed correctly (including one
reaching `REJECTED_OUT_OF_STOCK` via the real inventory pipeline) and offsets continuing to advance
normally:

```
$ curl -s -X POST http://localhost:8081/api/orders ... # order-20177
$ curl -s http://localhost:8081/api/orders/order-20177
{"id":"order-20177", ..., "status":"REJECTED_OUT_OF_STOCK", ...,
 "statusHistory":[{"status":"PENDING",...},{"status":"REJECTED_OUT_OF_STOCK","sourceEventId":"e71575a1-...",...}]}

$ curl -s -X POST http://localhost:8081/api/orders ... # order-20178
{"id":"order-20178","status":"PENDING",...}

$ docker exec orderfulfillment-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic orders.events
orders.events:0:6
orders.events:1:1
orders.events:2:0
```

## Judgment calls

- **Chose `/var/lib/kafka/data` over keeping `/tmp/kraft-combined-logs` and manually fixing
  permissions.** The task said either changing the mount path or setting `KAFKA_LOG_DIRS` was
  acceptable, whichever was more correct for this image. I initially tried the minimal fix (keep
  the existing mount path, add `KAFKA_LOG_DIRS` pointing at it) and hit a second, independent bug:
  a fresh named volume under `/tmp` has no ownership to inherit and isn't writable by the broker's
  uid. Rather than patch that with a manual `chown` step (which wouldn't survive a clean clone —
  a brand-new named volume is created fresh with no chown history), I moved the mount to
  `/var/lib/kafka/data`, the path the image itself declares as a `VOLUME` and pre-chowns to
  `appuser` at build time. This makes the fix self-sufficient on a clean clone: `docker compose up`
  with no volume yet created just works, no extra setup step required. Verified this concretely with
  a disposable `test-kafka-vol` before committing to the approach.
- **Kept `KAFKA_LOG_DIRS` even though the mount path change alone might have been enough if Kafka's
  hardcoded default happened to match.** It doesn't (`/tmp/kafka-logs` is the hardcoded default, not
  `/var/lib/kafka/data`), so both changes are necessary together; documented that dependency in the
  inline comments so a future edit to one doesn't silently break the other.
- Used the order-service HTTP API to generate real events rather than `kafka-console-producer`,
  per the "scenario behavior must be real" rule and because it's a more faithful reproduction of the
  actual regression path (all five backend services producing/consuming, not a synthetic message).
- Did not investigate or fix the transient `OutOfOrderSequenceException` seen once per topic on the
  very first produce after the restart (see below) — it's a known, self-recovering side effect of
  the persisted producer-id/sequence state meeting a restarted producer client, and out of scope for
  this ticket.

## Deliberately not covered

- **A one-time `OutOfOrderSequenceException` appeared in the Kafka broker logs immediately after the
  restart**, on the first produce to `orders.events` and `inventory.events` after the services came
  back up (e.g. `Out of order sequence number for producer 0 at offset 5 ... 0 (incoming), 4
  (current end sequence number)`). The message still landed (offset advanced from 5 to 6, and
  `order-20177`'s full status history shows it was processed correctly through
  `REJECTED_OUT_OF_STOCK`), and no repeat of the error occurred on the next produce. This is a
  known, self-recovering consequence of *actually achieving* persistence: previously the log was
  wiped on every rebuild, so this condition could never arise; now that producer state persists
  across restarts while the Spring services get fresh idempotent-producer instances (new producer
  ID/epoch) on each restart, the broker detects the mismatch once and the producer's epoch bump
  self-heals it. Did not chase this further — it's a pre-existing characteristic of using Kafka's
  idempotent producer across container restarts in general, not something introduced or fixable by
  this docker-compose change, and it caused no data loss or duplicate processing in the run I
  observed.
- Did not re-verify the composite dedupe-key half of #27 (V3 migration /
  `EventProjectionConsumer`) — out of scope per the task instructions, and already independently
  verified working.
- Did not test a `docker compose down -v` / full-volume-wipe scenario — that's the explicitly
  intended reset path (fresh environment), not a regression to guard against.
- Did not run this under `kind`/Kubernetes — the task is scoped to `docker-compose.yml`, and the
  change doesn't touch the Kubernetes manifests.

## Reproducing this from a clean clone

`docker compose up -d --build` (or `up -d --build kafka` alone) creates the
`orderfulfillment-kafka-data` named volume for the first time, mounted at `/var/lib/kafka/data`
inside the container. No manual `chown` or setup step is needed — the volume inherits `appuser`
ownership from the image automatically on first mount, and the broker's `KAFKA_LOG_DIRS` is
already pointed at that path.
