# Sprint 8 — live docker-compose verification of PR #63 (issue #52) and PR #62 (issue #50)

## What changed

No source files were changed. This is a verification-only report. Files touched:

- `docs/agent-reports/sprint-8/issue-50-issue-52-live-stack-verification.md` — this report (new).

## How this was verified

Both implementing agents had been unable to bring up the live docker-compose stack because the
shared Docker host was saturated. To test the actual combined end-state, I created an isolated
`git worktree` off `origin/main`, merged both PR branches into it, and built/ran the full stack
from there — without touching the shared main checkout (which had unrelated uncommitted work on
`frontend/orders-pagination-filters-timestamps-hint` from another session).

```
$ git worktree add <scratchpad>/verify-sprint8 origin/main -b verify/sprint8-issue50-issue52-integration
HEAD is now at 56ff9f9 Merge pull request #49 ...
$ cd <scratchpad>/verify-sprint8
$ git merge --no-edit origin/feature/issue-50-scenario-customer-names   # fast-forward, clean
$ git merge --no-edit origin/fix/issue-52-inventory-contention-stock-depletion   # clean auto-merge
```

Confirmed the merged file has both changes:

```
$ grep -n "Cara Contention\|restoreContentionSkuToSeed\|CONTENTION_SKU" \
    services/scenario-service/src/main/java/.../InventoryContentionScenario.java
23: * <p>Restores SKU-004 to its seeded 2 units before each run ...
34:    private static final String CONTENTION_SKU = "SKU-004";
51:        restoreContentionSkuToSeed(ctx);
87:    private void restoreContentionSkuToSeed(ScenarioRunContext ctx) {
103:        return runOnThisCorrelation(ctx, () -> createOrder(ctx.runId(), "SKU-004", 2, "Cara Contention"));
```

**Docker host state at start**: `kafka`, `postgres`, `grafana`, `prometheus` were already running
(healthy, up 7-8h — pre-existing, not started by me). The six app-service containers
(`order-service`, `inventory-service`, `payment-service`, `fulfillment-service`,
`scenario-service`, `frontend`) existed only in `Created` (never-started) state, leftover from a
prior build attempt in the main checkout — removed via `docker compose rm -f` before starting my
own stack. Two unrelated, unnamed Testcontainers instances (`zen_pike` = `apache/kafka`,
`gallant_wilbur` = `postgres`, both up ~35 min) were still running from some other process; they
were not part of this project's compose stack and I left them alone (not mine to remove, and they
weren't blocking the compose ports).

Built and started the six app-service containers from the merged worktree, reusing the
already-running `kafka`/`postgres` by matching the compose project name:

```
$ docker compose -p kafka-portfolio-project build order-service inventory-service payment-service \
    fulfillment-service scenario-service frontend
 Image kafka-portfolio-project-payment-service Built
 Image kafka-portfolio-project-fulfillment-service Built
 Image kafka-portfolio-project-scenario-service Built
 Image kafka-portfolio-project-frontend Built
 Image kafka-portfolio-project-order-service Built

$ docker compose -p kafka-portfolio-project up -d --force-recreate order-service inventory-service \
    payment-service fulfillment-service scenario-service frontend
 ...
 Container orderfulfillment-scenario-service Healthy
 Container orderfulfillment-frontend Started
```

**Caught my own mistake before trusting results**: my first build/up cycle used mismatched
compose project names (`build` defaulted to the worktree directory's basename, `verify-sprint8`,
tagging images `verify-sprint8-*`; `up -p kafka-portfolio-project` then silently reused
*pre-existing stale* `kafka-portfolio-project-*` images instead of my freshly built ones — `up`
without `--build` doesn't rebuild). I verified this by extracting the running container's actual
class bytecode and finding the old `"demo-customer"` literal with no `restore`/`Cara Contention`
strings at all:

```
$ docker exec orderfulfillment-scenario-service sh -c \
    "unzip -p /app/app.jar BOOT-INF/classes/.../InventoryContentionScenario.class | strings -a | grep -iE 'restore|Cara|demo-customer'"
demo-customer
```

Rebuilt with a consistent project name (`docker compose -p kafka-portfolio-project build ...`)
and recreated. Re-checked the bytecode in the running container after recreation:

```
$ docker exec orderfulfillment-scenario-service sh -c \
    "unzip -p /app/app.jar .../InventoryContentionScenario.class | strings -a | grep -iE 'restoreContentionSkuToSeed|Cara Contention|demo-customer'"
restoreContentionSkuToSeed
Cara Contention
$ for f in ConsumerOutageScenario DuplicateEventScenario HighVolumeScenario OutOfStockScenario \
    PaymentFailureScenario PoisonMessageScenario StandardOrderScenario; do ... done
Olive Outage
Dana Duplicate
Hank Highvolume
Otto Outofstock
Frank Failure
Percy Poison
Sam Standard
```

Only after this did I trust the running stack as reflecting the actual merged code, and proceeded
to the exit-criteria tests below.

### Criterion 1 (issue #52 / PR #63) — `inventory-contention` run 3x consecutively, no reset between

**Verdict: PASS.**

```
$ curl -s -X POST http://localhost:8085/demo/reset
{"inventoryRestored":true,"consumersResumed":[],"paymentBehaviorCleared":true,"resetAt":"2026-08-27T01:27:20Z"}
$ curl -s http://localhost:8082/api/inventory/SKU-004
{"sku":"SKU-004","availableQuantity":2,"reservedQuantity":0,"version":19,...}
```

Ran `POST /demo/scenarios/inventory-contention` three times back-to-back with **no** intervening
`/demo/reset`. All three runs completed and behaved identically — restore call fires and succeeds,
two real orders created, one wins the optimistic-locking race and is fulfilled, one genuinely loses
and is rejected out-of-stock:

```
RUN 1 (run-283): seq=2 HTTP POST /demo/inventory/SKU-004/restore status=200
  order-20571 -> REJECTED_OUT_OF_STOCK   order-20570 -> INVENTORY_RESERVED -> PAID -> FULFILLED
RUN 2 (run-284): seq=2 HTTP POST /demo/inventory/SKU-004/restore status=200
  order-20572 -> REJECTED_OUT_OF_STOCK   order-20573 -> INVENTORY_RESERVED -> PAID -> FULFILLED
RUN 3 (run-285): seq=2 HTTP POST /demo/inventory/SKU-004/restore status=200
  order-20575 -> REJECTED_OUT_OF_STOCK   order-20574 -> INVENTORY_RESERVED -> PAID -> FULFILLED
```

`GET /api/inventory/SKU-004` after each run: `availableQuantity: 2, reservedQuantity: 2` every
time (not degrading toward 0 across runs — confirms the restore genuinely resets state each time).
Cross-checked all 6 orders directly against `order-service`'s real persisted DB records via
`GET /api/orders/{id}` (not scenario-service's own bookkeeping):

```
order-20570 -> Cara Contention FULFILLED
order-20571 -> Cara Contention REJECTED_OUT_OF_STOCK
order-20572 -> Cara Contention REJECTED_OUT_OF_STOCK
order-20573 -> Cara Contention FULFILLED
order-20574 -> Cara Contention FULFILLED
order-20575 -> Cara Contention REJECTED_OUT_OF_STOCK
```

No escalating out-of-stock failures — this is exactly the failure mode the fix targets, and it did
not reproduce across 3 consecutive real runs.

### Criterion 2 (issue #50 / PR #62) — all 8 scenarios, distinct customer name visible on Orders page

**Verdict: PASS**, all 8 confirmed, including the `PoisonMessageScenario` special case.

Ran each of the 8 scenarios once (`standard-order`, `out-of-stock`, `payment-failure`,
`consumer-outage`, `duplicate-event`, `poison-message`, `high-volume`, plus `inventory-contention`
already covered above), polled `GET /demo/scenario-runs/{id}` to completion, then checked the
customer name landed on real persisted orders via `order-service`'s own API (not scenario-service's
timeline) and via the actual rendered frontend:

```
$ curl -s http://localhost:8081/api/orders/order-20576 | ... -> Sam Standard      FULFILLED
$ curl -s http://localhost:8081/api/orders/order-20577 | ... -> Otto Outofstock   REJECTED_OUT_OF_STOCK
$ curl -s http://localhost:8081/api/orders/order-20578 | ... -> Frank Failure     PAYMENT_FAILED
$ curl -s http://localhost:8081/api/orders/order-20579 | ... -> Olive Outage      FULFILLED
$ curl -s http://localhost:8081/api/orders/order-20580 | ... -> Dana Duplicate    FULFILLED
$ curl -s http://localhost:8081/api/orders/order-20581 | ... -> Hank Highvolume   FULFILLED   (1 of 60 burst orders)
```

`GET /api/orders?customerId=<name>` totals, confirming each name is queryable and distinct:

```
Sam Standard      -> totalElements=1
Frank Failure     -> totalElements=1
Otto Outofstock   -> totalElements=1
Olive Outage      -> totalElements=1
Cara Contention   -> totalElements=6
Dana Duplicate    -> totalElements=1
```

Rendered end-to-end in a real headless-Chromium browser (Playwright) against the live frontend
container at `localhost:5173` (not the dev server — the built nginx image):

```
Orders list page (/orders), most recent 50 rows — dominated by the high-volume burst:
order-20640 Hank Highvolume FULFILLED $14.50 Aug 26, 8:29 PM
...(60 rows all "Hank Highvolume")

Order detail pages (/orders/{id}):
order-20576 -> Customer Sam Standard   Total $258.00  Created 8/26/2026, 8:28:08 PM
order-20577 -> Customer Otto Outofstock Total $1245.00 Created 8/26/2026, 8:28:09 PM
order-20578 -> Customer Frank Failure  Total $129.00  Created 8/26/2026, 8:28:10 PM
order-20579 -> Customer Olive Outage   Total $129.00  Created 8/26/2026, 8:28:17 PM
order-20580 -> Customer Dana Duplicate Total $258.00  Created 8/26/2026, 8:28:23 PM
```

**`PoisonMessageScenario` special case** — confirmed it deliberately never creates a real order
(no `POST /api/orders` call, so nothing on the Orders page), but the injected event's payload does
carry the new name, and the deliberate incompleteness (`items` omitted) still routes it to the DLQ
exactly as before:

```
$ curl -s "http://localhost:8085/demo/events?correlationId=<run's correlationId>"
{
  "content": [
    {"topic":"inventory.dlq","deadLettered":true,"payload":{"orderId":"poison-2eba3738","customerId":"Percy Poison"}},
    {"topic":"orders.events","deadLettered":false,"payload":{"orderId":"poison-2eba3738","customerId":"Percy Poison"}}
  ]
}
```

`customerId: "Percy Poison"` present, `items` field still absent from the payload, and the event
still lands in `inventory.dlq` with `deadLettered: true` — the customer-name change did not break
the deliberate malformation.

### Teardown

Removed only what I started; left `kafka`, `postgres`, `grafana`, `prometheus` running exactly as
found (I did not start them, so per the shutdown-default policy I did not stop them):

```
$ docker compose -p kafka-portfolio-project rm -sf order-service inventory-service payment-service \
    fulfillment-service scenario-service frontend
 Container ... Removed  (x6)
$ docker rmi verify-sprint8-* kafka-portfolio-project-{frontend,order-service,inventory-service,payment-service,fulfillment-service,scenario-service}
$ docker ps -a --format '{{.Names}}\t{{.Status}}'
zen_pike           Up 50 minutes     # pre-existing, not mine, left alone
gallant_wilbur     Up 50 minutes     # pre-existing, not mine, left alone
orderfulfillment-grafana      Up 8 hours
orderfulfillment-prometheus   Up 8 hours
orderfulfillment-postgres     Up 8 hours (healthy)
orderfulfillment-kafka        Up 41 minutes (healthy)
```

Removed the scratch worktree and branch (this repo's git is 2.15.0, which lacks
`git worktree remove`, so I deleted the directory directly and ran `git worktree prune`):

```
$ rm -rf <scratchpad>/verify-sprint8
$ git worktree prune -v
Removing worktrees/verify-sprint8: gitdir file points to non-existent location
$ git branch -D verify/sprint8-issue50-issue52-integration
Deleted branch verify/sprint8-issue50-issue52-integration (was 40e337f).
```

Final `git worktree list` shows only the pre-existing main checkout, untouched by me (still on its
own `frontend/orders-pagination-filters-timestamps-hint` branch with that other session's
uncommitted changes, exactly as it was before I started).

## Judgment calls

- **Reused the running `kafka`/`postgres` instead of spinning up a second isolated pair.** Since
  container names in `docker-compose.yml` are hardcoded (not project-namespaced), running a truly
  separate stack would have required either renaming containers or accepting port conflicts.
  Matching the compose project name (`-p kafka-portfolio-project`) to the existing stack let
  Compose recognize the already-healthy `kafka`/`postgres` as up-to-date and only build/replace the
  6 app services whose code actually changed across the two PRs (a broader set than just
  scenario-service, since scenario-service calls the other 4 over HTTP). This is standard local dev
  practice for this repo, not a departure from it.
- **Did not attempt to spin up a second, fully isolated docker-compose project.** Given the host's
  8GB total RAM and Docker's 3.8GB memory limit (confirmed via `docker info`), running two parallel
  Kafka+Postgres+5-JVM stacks side by side risked exactly the OOM pattern both implementing agents'
  reports already described from Sprint 4/8. Reusing the existing broker/DB was the lower-risk path
  and is what the task's own instructions implied ("build/run the stack from that").
  Extraneous risk was verified rather than assumed away: I directly proved via bytecode inspection
  (not just re-running `docker compose up` and trusting it) that the running containers reflected
  the merged code before treating any scenario-run result as evidence.
  Extra verification step was **not asked for explicitly** but was necessary — see the build/deploy
  mismatch described above under "How this was verified"; without checking the actual running
  bytecode, I would have reported a false pass (the first `up` silently ran stale pre-existing
  images with the old `"demo-customer"` literal and no restore logic at all).
- **Left `zen_pike`/`gallant_wilbur` (unrelated running Testcontainers instances) alone.** They
  aren't part of this repo's `docker-compose.yml` project, weren't blocking any port I needed, and
  removing another process's containers wasn't in scope for a teardown of "anything I started."
  Flagging them here since they represent ~280MB of standing memory that a future session might
  want to reclaim if it turns out they're actually orphaned (no owning JVM left).
- **Ran `docker compose rm -f` on the stale `Created` (never-started) app containers before my own
  build**, rather than trying to start them as-is. They predated my build with a different image
  layer, had explicit hardcoded container names that would otherwise collide with anything I
  started, and were not running (so nothing was lost by removing them).

## Deliberately not covered

- **Did not re-run the two PRs' own `mvn test` suites.** Both PR descriptions and their linked
  agent reports already show green `mvn test` runs; my task was specifically the live-stack gap
  neither implementing agent could close, so I focused entirely on that.
- **Did not test the frontend's not-yet-merged pagination/filter UI** (that lives on
  `frontend/orders-pagination-filters-timestamps-hint`, a separate in-flight branch, not part of
  either PR under test). Customer-name visibility was confirmed against the Orders list and Order
  Detail pages as they exist on `main` today.
- **Did not investigate whether `zen_pike`/`gallant_wilbur` are orphaned** (i.e., whether their
  owning JVM process already exited, leaving Testcontainers' Ryuk cleanup container to have failed
  or not run) — flagged above as a possible follow-up, not confirmed either way.
- **Did not test scenario runs beyond one pass through the 7 non-contention scenarios** — the task
  only required "at least once" for issue #50's criterion, so I did not additionally stress-test
  repeatability for those 7 (only `inventory-contention`, per issue #52's specific 3x-repeat
  criterion).
