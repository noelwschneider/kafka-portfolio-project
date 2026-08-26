# Scenario 8 (High-Volume Batch): stock depletion across runs, and the SSE refetch storm

Issue #46. Two independent defects, neither of them a resource leak or memory exhaustion.

**Root cause of the escalating failures:** each high-volume run permanently consumes 60 of SKU-003's
100 seeded units and never restores them, so run 1 passes (60 available), run 2 fails 20 of 60, and
run 3 fails all 60. Deterministic arithmetic, not degradation.

**Root cause of the page freeze:** the scenario run detail page refetched the entire run document on
every SSE `timeline-entry` message. A high-volume run emits ~513 of them against a document that
grows to ~140KB — O(n²) work in the browser.

## What changed

| File | Change |
| --- | --- |
| `services/scenario-service/src/main/java/com/orderfulfillment/scenario/catalog/SeedInventory.java` | **New.** Single source of truth for the four demo SKUs' seed quantities, plus `HIGH_VOLUME_SKU`. Extracted so the scenario and the reset service cannot disagree about the number. |
| `services/scenario-service/src/main/java/com/orderfulfillment/scenario/scenarios/HighVolumeScenario.java` | Restores SKU-003 to seed before the burst (`restoreBurstSkuToSeed`), recorded on the timeline with its real status code and non-fatal on failure. Corrected the class Javadoc, which asserted "100 seeded, so no artificial restocking is needed" — the claim that caused this. |
| `services/scenario-service/src/main/java/com/orderfulfillment/scenario/admin/DemoResetService.java` | Reads seed quantities from `SeedInventory` instead of its own private copy. Behaviour unchanged. |
| `services/scenario-service/src/main/java/com/orderfulfillment/scenario/clients/InventoryServiceClient.java` | `restoreInventory` returns the real HTTP status code instead of `void`, matching `PaymentServiceClient.setBehavior`, so the caller can record what actually happened. |
| `services/scenario-service/src/test/java/com/orderfulfillment/scenario/HighVolumeScenarioIntegrationTest.java` | Stubs `POST /demo/inventory/SKU-003/restore` on the Inventory WireMock, which the scenario now calls. |
| `frontend/src/pages/ScenarioRunDetailPage.tsx` | Applies each SSE frame to the cached run via `setQueryData` instead of `invalidateQueries` per message. One reconciling refetch on `run-status` only. |

Nothing in `infrastructure/` changed. No manifest, resource limit, JVM flag, or deployment was
touched, and no production redeploy was run — see Judgment calls.

## How this was verified

### 1. The live failures, from the production run records

The three runs the developer reported are `run-129`/`130`/`131`. Their elapsed times are what
disproves the degradation hypothesis — the failures got **faster**, not slower:

```
$ curl -s "https://fulfillment-demo.noelschneider.com/svc/scenario/demo/scenario-runs?size=12"
run-131  high-volume  FAILED     elapsedMs 3197
run-130  high-volume  FAILED     elapsedMs 9351
run-129  high-volume  COMPLETED  elapsedMs 11350
```

A 60-order burst that fails on the 180s production watch timeout cannot finish in 3.2 seconds. The
timelines say exactly what happened instead:

```
=== run-129 === status: COMPLETED
  InventoryReserved 60 | Order FULFILLED 60
  SUMMARY: {"ordersSubmitted": 60, "ordersFulfilled": 60, "ordersNotFulfilled": 0}
=== run-130 === status: FAILED  "20 of 60 orders ... did not reach FULFILLED"
  InventoryReserved 40 | InventoryReservationFailed 20 | Order REJECTED_OUT_OF_STOCK 20
  SUMMARY: {"ordersSubmitted": 60, "ordersFulfilled": 40, "ordersNotFulfilled": 20}
=== run-131 === status: FAILED  "60 of 60 orders ... did not reach FULFILLED"
  InventoryReservationFailed 60 | Order REJECTED_OUT_OF_STOCK 60
  SUMMARY: {"ordersSubmitted": 60, "ordersFulfilled": 0, "ordersNotFulfilled": 60}
```

60 + 40 + 0 = 100, exactly SKU-003's seeded stock. Inventory confirmed it was spent:

```
$ curl -s ".../svc/inventory/api/inventory"
SKU-003  availableQuantity 100  reservedQuantity 100     # free = 0
```

A fulfilled order's reservation is never released, so the stock is consumed permanently, not
temporarily held.

### 2. Ruling out memory / leak / node exhaustion, with real evidence

Pods had restarted ~30 min earlier for the developer's `/deploy` and had **zero restarts since** — the
three failing runs OOMKilled nothing:

```
$ kubectl get pods -n orderfulfillment
frontend-7d4c89b6c9-89bgf            1/1  Running  0  29m
fulfillment-service-c9cf7cf5-slr9h   1/1  Running  0  31m
inventory-service-5d7f8f4597-8g4nt   1/1  Running  0  32m
kafka-cbf7b9bd4-mfgx7                1/1  Running  4 (5d5h ago)  5d19h
order-service-7b68f85d4-64hxt        1/1  Running  0  33m
payment-service-84db4d94d9-84fw2     1/1  Running  0  31m
postgres-894c4bc65-88zws             1/1  Running  4 (5d5h ago)  5d19h
scenario-service-74ffb455f-xr8b5     1/1  Running  0  30m
```

Host memory held flat across a full run (5s samples, `ssh kafka-demo-box free -m`):

```
[t+5s]  used=3533Mi avail=286Mi      [t+35s] used=3539Mi avail=280Mi
[t+10s] used=3537Mi avail=282Mi      [t+40s] used=3535Mi avail=284Mi
[t+15s] used=3529Mi avail=290Mi      [t+45s] used=3541Mi avail=278Mi
[t+20s] used=3531Mi avail=288Mi      [t+50s] used=3556Mi avail=263Mi
[t+25s] used=3531Mi avail=288Mi      [t+55s] used=3547Mi avail=272Mi
[t+30s] used=3533Mi avail=286Mi      [t+60s] used=3548Mi avail=271Mi
```

No spike, no trend. The ~89%/85% idle figure from the Sprint 7 investigation is real but is not what
broke Scenario 8.

### 3. Controlled experiment on production — the falsifiable prediction

Reset (restores seed stock), then one run. Hypothesis predicts a clean pass:

```
$ curl -X POST ".../svc/scenario/demo/reset"
{"inventoryRestored":true,"consumersResumed":[],"paymentBehaviorCleared":true,...}
SKU-003 available=100 reserved=0

$ curl -X POST ".../svc/scenario/demo/scenarios/high-volume"
HTTP 202   # returns immediately — the POST does not block
run-132 status: COMPLETED | elapsedMs: 6908
SUMMARY: {"ordersSubmitted": 60, "ordersFulfilled": 60, "ordersNotFulfilled": 0}
```

**run-132 was the fastest of all four runs (6.9s) and passed 60/60, immediately after the three
"degrading" runs.** If anything were accumulating, the fourth run in sequence would be the worst.

Then, with 40 units left, the hypothesis predicts the next run fails at *exactly* 20 of 60:

```
SKU-003 available=100 reserved=60 -> free=40
$ curl -X POST ".../svc/scenario/demo/scenarios/high-volume"
run-133 status: FAILED | elapsedMs: 4782
error: 20 of 60 orders in the high-volume batch did not reach FULFILLED
SUMMARY: {"ordersSubmitted": 60, "ordersFulfilled": 40, "ordersNotFulfilled": 20}
```

Exact match to the prediction and to the developer's second attempt.

### 4. Reproduced locally against the pre-fix build (control)

```
=== PRE-FIX CONTROL (old container) ===
reset HTTP 200
run 1:  run-276 COMPLETED elapsed= 11148 err= None
run 2:  run-277 FAILED    elapsed= 3422  err= 20 of 60 orders ... did not reach FULFILLED
```

Same signature off the demo box — a code defect, not box-specific.

### 5. The fix, verified: three consecutive runs, no reset, from fully depleted stock

```
=== POST-FIX: three consecutive runs, NO reset between ===
stock before: SKU-003 available=100 reserved=100 free=0     # fully depleted
run 1: run-278 COMPLETED elapsed=6123
       RESTORE ENTRY: POST /demo/inventory/SKU-003/restore {"statusCode": 200}
       SUMMARY: {"ordersSubmitted":60,"ordersFulfilled":60,"ordersNotFulfilled":0}
run 2: run-279 COMPLETED elapsed=2739
       RESTORE ENTRY: POST /demo/inventory/SKU-003/restore {"statusCode": 200}
       SUMMARY: {"ordersSubmitted":60,"ordersFulfilled":60,"ordersNotFulfilled":0}
run 3: run-280 COMPLETED elapsed=2834
       RESTORE ENTRY: POST /demo/inventory/SKU-003/restore {"statusCode": 200}
       SUMMARY: {"ordersSubmitted":60,"ordersFulfilled":60,"ordersNotFulfilled":0}
```

Run 1 began at `free=0` — the exact state that failed 60/60 pre-fix — and passed.

### 6. Full suite, including both CI-excluded tests

They are excluded in CI only, so a local `mvn test` runs them:

```
$ mvn -B -pl services/common,services/scenario-service -am test
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] scenario-service ................................... SUCCESS [ 57.745 s]
[INFO] BUILD SUCCESS
```

### 7. The frontend refetch storm, measured

Captured the raw SSE stream of one real run:

```
$ curl -s -N "$S/demo/scenario-runs/run-281/stream" > sse.txt
timeline-entry frames: 513
run-status frames: 1

$ grep -A1 '^event:timeline-entry' sse.txt | head -2
event:timeline-entry
data:{"sequence":2,"kind":"HTTP","label":"POST /demo/inventory/SKU-003/restore",
      "occurredAt":"2026-08-26T23:20:34.068373089Z","detail":{"statusCode":200}}

$ grep -A1 '^event:run-status' sse.txt | head -2
event:run-status
data:{"orderId":"","status":"COMPLETED","completedAt":"2026-08-26T23:20:36.920933674Z"}
```

The old handler called `invalidateQueries` on each of those 513 frames, and the run document is not
small:

```
$ curl -s ".../demo/scenario-runs/run-129" -o /dev/null -w 'bytes=%{size_download}\n'
bytes=140496
```

So: ~513 refetches of a document growing to ~140KB (tens of MB of JSON parsed), each re-rendering a
list growing to ~520 `<li>` elements, all inside the few seconds the burst takes. Every other
scenario emits ~10-20 entries, which is why this never showed up before. The new handler makes **1**
refetch, on the single `run-status` frame. The captured frame shapes are exactly what the new code
parses — including `orderId:""` rather than `null`, which the merge handles explicitly.

```
$ npx tsc --noEmit    # exit 0
$ npm run lint        # oxlint, exit 0
$ npm run build       # tsc -b && vite build — "✓ built in 910ms", exit 0
```

### 8. Production demo state at end of turn

```
$ curl -X POST ".../svc/scenario/demo/reset"
{"inventoryRestored":true,"consumersResumed":[],"paymentBehaviorCleared":true,
 "resetAt":"2026-08-26T23:13:31.539003738Z"}
SKU-001 available=10  reserved=0
SKU-002 available=5   reserved=0
SKU-003 available=100 reserved=0
SKU-004 available=2   reserved=0
```

## Is the production demo currently degraded?

**No — it is reset and working, but the defect is still deployed.** The box runs the pre-fix image;
the fix is not shipped (see Judgment calls). Concretely:

- All pods Running, zero restarts, memory normal, all four SKUs at seed.
- A visitor who runs Scenario 8 **once** gets a correct, passing run.
- A visitor who runs it **twice within 15 minutes** still sees the 20-of-60 failure, and the page will
  still freeze on the timeline. Both are fixed in this branch, not on the box.
- The 15-minute idle auto-reset (`application-production.yml`, production profile only) restores seed
  stock on an idle box, so this partially self-heals between visitors. It does not help back-to-back
  runs, which is exactly what the developer did.

## Judgment calls

- **Did not run a production redeploy.** The task allows production fixes, but shipping this needs the
  `workflow_dispatch` GHCR image build plus `redeploy.sh` across the fleet — the precise operation
  ADR-011 exists because it took this box down twice, on a box with ~270-290Mi of real headroom, an
  hour after the developer's own `/deploy`. The demo is currently functional and the failure mode is
  bounded and self-healing on a 15-minute timer, so the cost of waiting for an explicit go-ahead is
  low and the cost of a bad redeploy is another outage. Landing the code and deploying it are separate
  decisions; I made the first and am flagging the second.
- **Ran two extra scenario runs on production beyond the first.** The brief said to decide based on
  observed degradation. The first run showed none — flat memory, zero restarts, and it was the
  *fastest* run of the four — so a second run was justified to test the falsifiable "exactly 20 of 60"
  prediction, which is what turned a plausible story into a proven one. I reset afterwards.
- **Reset production before finishing diagnosis.** Safe because `DemoResetService`'s Javadoc states
  reset deliberately does not delete `scenario_runs`, `scenario_run_timeline`, or the `events`
  projection, and I had already captured run-129/130/131's timelines. It destroyed no evidence and
  un-broke the demo immediately, which is the least invasive recovery available.
- **Restore-before-burst rather than restore-after, or lowering the burst size.** Restoring first makes
  the scenario state its own precondition, exactly as it already does for payment behaviour one line
  above. Restoring after would leave the demo correct only if every run completes. Lowering the burst
  below 33 (so three runs fit in 100 units) would change what the scenario demonstrates and only
  postpones the problem to run 4.
- **Made the restore non-fatal.** If it fails, the run reports the honest downstream out-of-stock
  outcome plus a timeline entry carrying the restore error, rather than masking the real result behind
  a different exception. Consistent with `ConsumerLagService`'s "measurement aid, not a correctness
  gate" stance.
- **Extracted `SeedInventory` instead of duplicating `100` or reaching into `DemoResetService`.** A
  scenario depending on the admin/reset service inverts the dependency; duplicating the constant lets
  the two drift silently. The extraction is mechanical and `DemoResetService`'s behaviour is unchanged.
- **Fixed the frontend by merging SSE frames rather than throttling the refetch.** Throttling would
  have cut ~513 refetches to ~20 and left the O(n²) shape intact for a larger burst. The stream already
  carries the whole entry, so refetching to obtain data already in hand is the actual defect. A
  malformed frame still falls back to `invalidateQueries`, and duplicate sequences are ignored so an
  `EventSource` reconnect cannot double-render.

## Deliberately not covered

- **The fix is not deployed to production.** This is the single most important gap. Shipping it needs:
  run `.github/workflows/build-images.yml` (`workflow_dispatch`), then
  `infrastructure/kubernetes/production/redeploy.sh`, watching `kubectl top nodes` live per the Sprint
  7 investigation's recommendation. Only `scenario-service` and `frontend` actually changed, so a
  narrower restart of just those two Deployments — sequentially, waiting for health between — is
  lower-risk than the full six-Deployment `redeploy.sh` and worth considering.
- **The frontend fix was not exercised in a real browser.** I have no browser tooling in this session.
  It is verified by `tsc --noEmit`, `oxlint`, `vite build`, and by confirming the captured SSE frame
  shapes match what the new code parses — but nobody has watched the page actually not freeze. That
  confirmation should happen right after deploy, by running Scenario 8 on the live site.
- **The CI-excluded tests are a *different* defect, and I did not fix them.** This is the answer to the
  brief's "check, don't assume" question: `HighVolumeScenarioIntegrationTest` stubs Inventory Service
  with WireMock, so there is no real stock and the depletion defect cannot occur there. Its flakiness
  is almost certainly `stubSequentialOrderCreation`'s WireMock **scenario-state machine being driven by
  genuinely concurrent requests** — the burst submits with `high-volume-submission-concurrency` threads,
  and WireMock scenario-state transitions are not safe to race, so two concurrent POSTs can observe the
  same state (duplicate order id) or arrive when no stub matches. Under CI resource contention the
  interleaving changes, which fits "passes locally, intermittently fails on shared runners" exactly. I
  left the exclusion in place and did not touch the Tier 2 backlog item. **My findings do not confirm
  the connection the brief hypothesised — they argue against it.** Both suites passed locally 15/15
  here, which is not evidence either way for a race.
- **`InventoryContentionScenario` has the identical defect and I did not fix it** (out of scope per the
  brief). SKU-004 is seeded at 2 and each run consumes both units, never released — so a second
  inventory-contention run without a reset has zero stock and both orders fail for the wrong reason
  (out-of-stock rather than the optimistic-locking contention it exists to demonstrate). Live evidence:
  after run-128 the SKU sat at `available=2 reserved=2`, i.e. free=0. It is a one-line fix of the same
  shape (`restoreBurstSkuToSeed` with `SKU-004`), and `SeedInventory` is already in place for it.
  Recommend a follow-up ticket; it is a strictly worse failure than Scenario 8's because it silently
  demonstrates the wrong thing rather than erroring.
- **The `docs/scenarios.md` Scenario 8 description was not checked for whether it should mention the
  restore.** The contract file may or may not need a line saying the scenario re-seeds its SKU; I did
  not modify a frozen contract doc under incident conditions.
- **No load/soak testing.** Three consecutive runs locally and four on production is not "does this
  hold at 20 runs". Nothing suggests it would not, but it is untested.
- **The ~800Mi k3s control-plane overhead flagged by the Sprint 7 investigation is untouched.** Still a
  real gap in ADR-010/ADR-011's capacity model, still unmeasured directly. Not this incident's cause.
- **Local Docker Compose stack left running with the fixed `scenario-service` image.** It was already
  running before this session, so per the standing preference I did not shut it down — but its
  `scenario-service` container now runs this branch's un-merged code, and its demo state was reset at
  the end. A `docker compose build scenario-service` off `main` reverts it.
- **No ADR was added.** This is a scenario-logic bug and a frontend data-fetching bug, not an
  infrastructure constraint or a decision that shapes future deploys, so it does not meet the bar for
  `docs/adr/`. The corrected Javadoc on `HighVolumeScenario` and `SeedInventory` carry the lesson at
  the place someone would next reintroduce it.
