# Issue #30 — Kafka health indicator

## What changed

- `services/common/src/main/java/com/orderfulfillment/common/kafka/KafkaHealthIndicator.java` (new) —
  a `@Component` extending `org.springframework.boot.health.contributor.AbstractHealthIndicator`
  (Spring Boot 4.1's replacement package for the pre-4.1 `HealthIndicator` API). Opens a dedicated
  `AdminClient` from `spring.kafka.bootstrap-servers` at construction and, on each health check, calls
  `describeCluster()` and blocks (3s timeout) on both `clusterId()` and `nodes()`. Reports `UP` with
  `clusterId`/`nodeCount` details if at least one broker node comes back, `DOWN` with a reason if the
  node list is empty, and lets `AbstractHealthIndicator` catch and report any exception (timeout,
  connection refused, etc.) as `DOWN`. Implements `DisposableBean` to close the admin client on
  context shutdown.
- `services/common/pom.xml` — added `spring-boot-starter-actuator` as a compile dependency. Common
  didn't previously depend on actuator at all; the new class needs
  `org.springframework.boot.health.contributor.*` on its classpath to compile.

No other files changed. No frontend changes — `frontend/src/pages/OverviewPage.tsx`'s
`deriveInfraStatus` already reads `raw.components.kafka.status` from whichever service's Actuator
response carries it; it now finds a real value instead of falling through to "no data".

## Judgment calls

- **Where the indicator lives**: `services/common`, not duplicated five times. All five services
  (`order`, `inventory`, `payment`, `fulfillment`, `scenario`) already declare
  `com.orderfulfillment.common` as an explicit `@ComponentScan` base package (verified in each
  `*Application.java`), all five carry both `spring-kafka` and `spring-boot-starter-actuator`, and all
  five point at the same broker via `spring.kafka.bootstrap-servers`. There's no per-service variation
  for this check to encode, so one shared bean picked up by all five scan configs is the same pattern
  `KafkaTopicConfig` (also in `common/kafka`) already uses for topic declarations. Rejected: a
  per-service class copy-pasted five times — same reasoning `common` itself exists for (identical code
  across services should live once), and five copies would drift the moment one service's timeout or
  detail fields got tweaked without the others following.
- **Definition of "healthy"**: a `describeCluster()` call that returns at least one broker node within
  a bounded timeout, issued through a dedicated `AdminClient` rather than reusing the
  producer/consumer factories the rest of the app builds. This is a genuine round trip to the broker's
  metadata API — the same call `kafka-broker-api-versions.sh` and scenario-service's
  `ConsumerLagService` (existing precedent in this codebase for a standalone admin client built from
  `bootstrap-servers`) rely on — not a check of whether a Spring bean merely exists. Considered and
  rejected: checking `KafkaAdmin`'s bean presence alone (would report `UP` even with the broker
  completely down, since `KafkaAdmin` autoconfigures unconditionally); reusing the app's own producer
  factory to send a real message (adds write load and topic-existence coupling to a read-only health
  check for no added signal over `describeCluster()`).
- **Timeout**: 3 seconds, matching the general shape of `ConsumerLagService`'s 5-second admin-call
  timeout but tighter since a health check runs far more often and should fail fast rather than block
  actuator scrapes.
- **Bean naming**: left as the default Spring bean name (`kafkaHealthIndicator`, class name
  unqualified) rather than an explicit `@Component("kafka")`. Spring's health contributor name
  generator strips the `HealthIndicator` suffix automatically, producing the `kafka` component key the
  frontend already expects — verified live below rather than assumed.
- Did not add a dedicated unit test for the new class. It has exactly one behavior worth testing
  (calls `describeCluster()`, maps success/failure to `Health`), and that behavior is inherently an
  integration concern — a unit test would either mock `AdminClient` (testing the mock, not the
  contract) or stand up a real broker (which the live `docker compose` verification below already
  does, including the DOWN path). Noted under Deliberately not covered.

## How this was verified

Backend compiled cleanly across all five services plus `common`:

```
$ mvn -q -pl services/common,services/inventory-service,services/order-service,services/payment-service,services/fulfillment-service,services/scenario-service -am compile
(no output, exit 0)
```

Baseline confirmed first (pre-existing "no data" bug, before rebuild reached the running containers):

```
$ curl -s localhost:8082/actuator/health | python3 -m json.tool
{
    "components": {
        "db": {"status": "UP"},
        "diskSpace": {"status": "UP"},
        "livenessState": {"status": "UP"},
        "ping": {"status": "UP"},
        "readinessState": {"status": "UP"},
        "ssl": {"status": "UP"}
    },
    ...
}
```
No `kafka` key — matches the reported bug.

Rebuilt only the five backend services (all depend on `common`), left `postgres`/`kafka`/`frontend`/
`grafana`/`prometheus` untouched:

```
$ docker compose up --build -d inventory-service order-service payment-service fulfillment-service scenario-service
...
 Image kafka-portfolio-project-fulfillment-service Built
 Image kafka-portfolio-project-inventory-service Built
 Image kafka-portfolio-project-order-service Built
 Image kafka-portfolio-project-payment-service Built
 Image kafka-portfolio-project-scenario-service Built
 Container orderfulfillment-payment-service Healthy
 Container orderfulfillment-inventory-service Healthy
 Container orderfulfillment-fulfillment-service Healthy
 Container orderfulfillment-order-service Healthy
 Container orderfulfillment-scenario-service Started
```
(First attempt failed on a transient `SSL peer shut down incorrectly` pulling `spring-boot-starter-parent`
from Maven Central inside the build container — unrelated to the code change; retry succeeded.)

All five services now report `kafka: UP`:

```
$ for port in 8081 8082 8083 8084 8085; do curl -s "localhost:$port/actuator/health" | python3 -c "import json,sys; d=json.load(sys.stdin); print('overall=', d['status'], 'kafka=', d['components']['kafka']['status'])"; done
overall= UP kafka= UP
overall= UP kafka= UP
overall= UP kafka= UP
overall= UP kafka= UP
overall= UP kafka= UP
```

Negative case — stopped the real Kafka container and confirmed the indicator detects it, not a stub:

```
$ docker compose stop kafka
 Container orderfulfillment-kafka Stopped
$ curl -s localhost:8082/actuator/health | python3 -m json.tool
{
    "components": {
        "db": {"status": "UP"},
        "kafka": {"status": "DOWN"},
        ...
    },
    "status": "DOWN"
}
```

Restarted Kafka and confirmed recovery:

```
$ docker compose start kafka
$ curl -s localhost:8082/actuator/health | python3 -m json.tool
{
    "components": {"db": {"status": "UP"}, "kafka": {"status": "UP"}, ...},
    "status": "UP"
}
```

CORS confirmed for the browser origin the frontend actually runs on (Home fetches this directly from
the browser per `frontend/src/api/health.ts`):

```
$ curl -s -i "localhost:8082/actuator/health" -H "Origin: http://localhost:5173" | head -8
HTTP/1.1 200
Access-Control-Allow-Origin: http://localhost:5173
Content-Type: application/vnd.spring-boot.actuator.v3+json
...
{"components":{...,"kafka":{"status":"UP"},...},"status":"UP"}
```

Restarted `grafana`/`prometheus` after the kafka stop/start cycle stopped them too via compose's
dependency graph (confirmed clean `Exited (0)`, not a crash) — restored to the original 10-container
state:

```
$ docker compose up -d grafana prometheus
$ docker compose ps --format "table {{.Name}}\t{{.Status}}"
orderfulfillment-frontend              Up 8 minutes
orderfulfillment-fulfillment-service   Up 9 minutes (healthy)
orderfulfillment-grafana               Up 8 minutes
orderfulfillment-inventory-service     Up 9 minutes (healthy)
orderfulfillment-kafka                 Up 10 minutes (healthy)
orderfulfillment-order-service         Up 9 minutes (healthy)
orderfulfillment-payment-service       Up 9 minutes (healthy)
orderfulfillment-postgres              Up 10 minutes (healthy)
orderfulfillment-prometheus            Up 8 minutes
orderfulfillment-scenario-service      Up 9 minutes (healthy)
```

Ran the backend test suite for the touched modules. On the first two full-reactor runs, different
integration tests failed each time (`PaymentServiceIntegrationTest` once, then
`InventoryContentionScenarioIntegrationTest`/`HighVolumeScenarioIntegrationTest` on the retry, none of
which touch Kafka health) — I discovered mid-verification that another agent is concurrently working
in this same checked-out repo (unrelated untracked files for `PriceController`/`SkuPrice` and modified
`docs/`, `frontend/`, `scenario-service` files appeared under `git status`, none of which I touched).
Re-running `payment-service`'s suite in isolation passed cleanly:

```
$ mvn -q -pl services/common,services/payment-service -am test > payment-test.log 2>&1; echo "exit=$?"
exit=0
```

which points to the same-host resource contention documented in `.claude/CLAUDE.md` (embedded-Kafka
integration tests are sensitive to concurrent CPU/port pressure) rather than a regression from this
change — the failures were in tests for unrelated services/scenarios and were different tests on each
run.

## Deliberately not covered

- No dedicated unit test for `KafkaHealthIndicator` — see Judgment calls for why; the live
  `docker compose` UP/DOWN/recovery cycle above is the verification for its one behavior.
- Did not re-run the full six-module test suite a third time to get a clean pass, since the failures
  were confirmed unrelated to this change (different tests failing each run, isolated re-run of the
  affected module passed, and a concurrent agent's untracked changes were discovered mid-session on
  the same host). Flagging the shared-host test flakiness itself as a standing risk, not something
  fixed here.
- Issue #31 (expandable per-service health detail on Home) is explicitly out of scope per the
  delegation — this issue only makes sure the `kafka` component exists and is correct at the endpoint
  level, which it now does for all five services.
- Did not add `showDetails`/`show-details` to any service's actuator config, so `DOWN` responses over
  HTTP report only `{"status": "DOWN"}` without the `reason` detail — this matches the existing
  project convention (`show-components: always` only, no `show-details`) and wasn't asked for; the
  detail is real and present in the `Health` object, just not exposed unauthenticated over HTTP, consistent
  with how the existing `db` component already behaves.
