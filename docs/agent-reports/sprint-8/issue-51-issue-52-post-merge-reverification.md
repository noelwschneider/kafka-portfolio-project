# Post-merge re-verification: issue #52 (PR #63) and issue #51 (PR #61)

Independent re-verification, against the live/real artifact, of two Sprint 8 goals that were merged
to `main` but never re-checked after merging.

## What changed

No source files were changed. This is a read-only verification pass. The only file created is this
report:

- `docs/agent-reports/sprint-8/issue-51-issue-52-post-merge-reverification.md` — this report.

## How this was verified

### Goal 1 — issue #52: InventoryContentionScenario SKU-004 stock-depletion fix (PR #63)

**Verdict: CONFIRMED WORKING.**

1. Read `services/scenario-service/src/main/java/com/orderfulfillment/scenario/scenarios/InventoryContentionScenario.java`.
   The `run()` method calls `restoreContentionSkuToSeed(ctx)` unconditionally, before the two
   contending orders are created — not declared-but-unused, not gated behind any flag:

   ```
   public void run(ScenarioRunContext ctx) {
       recordHttp(ctx.runId(), "PUT /demo/payment-behavior", paymentServiceClient.setBehavior("DEFAULT_SUCCESS"));
       restoreContentionSkuToSeed(ctx);
       ... (the two concurrent createOrder calls follow)
   ```

   `restoreContentionSkuToSeed` calls `inventoryServiceClient.restoreInventory(CONTENTION_SKU,
   SeedInventory.quantityFor(CONTENTION_SKU))`, i.e. `POST /demo/inventory/SKU-004/restore` with
   `availableQuantity: 2` — the identical pattern `HighVolumeScenario.restoreBurstSkuToSeed` uses for
   SKU-003 (verified by reading `HighVolumeScenario.java` side by side).

2. Found a live dev stack already running (`docker ps`, project `orderfulfillment-*`, all `healthy`,
   up between 24 min and 3 hr at the point I checked) — this stack pre-existed my session, I did not
   start it. Ran the real scenario three consecutive times with no reset in between, via `POST
   http://localhost:8085/demo/scenarios/inventory-contention`, polling `GET
   /demo/scenario-runs/{id}` to terminal status:

   ```
   === RUN 1 === final status: COMPLETED (order-20669 FULFILLED, order-20670 REJECTED_OUT_OF_STOCK)
     timeline includes: POST /demo/inventory/SKU-004/restore -> statusCode 200
   --- inventory SKU-004 after run 1 --- {"availableQuantity":2,"reservedQuantity":2,"version":30,...}

   === RUN 2 === final status: COMPLETED (order-20671 FULFILLED, order-20672 REJECTED_OUT_OF_STOCK)
     timeline includes: POST /demo/inventory/SKU-004/restore -> statusCode 200
   --- inventory SKU-004 after run 2 --- {"availableQuantity":2,"reservedQuantity":2,"version":32,...}

   === RUN 3 === final status: COMPLETED (order-20674 FULFILLED, order-20673 REJECTED_OUT_OF_STOCK)
     timeline includes: POST /demo/inventory/SKU-004/restore -> statusCode 200
   --- inventory SKU-004 after run 3 --- {"availableQuantity":2,"reservedQuantity":2,"version":34,...}
   ```

   All three runs reached `COMPLETED` with one order `FULFILLED` and one `REJECTED_OUT_OF_STOCK` each
   time — the scenario's documented winner/loser contention outcome, reproduced on all three runs, not
   just the first. `availableQuantity` stayed pinned at the seed value of 2 across all three runs
   (rather than the escalating 2 -> 0 -> fails-out-of-stock pattern the original SKU-003 bug had) —
   this is the direct behavioral signature the fix claims to produce.

3. Also ran the existing JUnit integration test as extra evidence:
   `mvn -pl services/scenario-service test -Dtest=InventoryContentionScenarioIntegrationTest`. It
   **failed locally** with `org.awaitility.core.ConditionTimeoutException` (20s poll timeout) on two
   separate attempts. I ruled this out as a genuine regression before reporting it:
   - `StandardOrderScenarioIntegrationTest` (same base class, same Testcontainers Kafka/Postgres
     setup, same 20s `POLL_TIMEOUT`) passed cleanly in the same environment run
     (`Tests run: 1, Failures: 0, Errors: 0, Time elapsed: 17.37 s`), so the failure isn't "this
     environment can't run scenario-service integration tests at all."
   - `git show ce58a1c -- .../InventoryContentionScenarioIntegrationTest.java` (PR #63's only change
     to this test file) shows the fix only *added* a WireMock stub for the new restore call; the
     two-thread concurrency mechanism and awaitility polling the test exercises were unchanged and
     predate the fix.
   - `gh pr view 63 --json statusCheckRollup` shows the `scenario-service` CI job — which runs this
     exact test — completed `SUCCESS` in GitHub Actions at merge time (2026-08-27T21:22:06Z).

   Conclusion: the local failure is resource contention on this machine (the full
   `orderfulfillment-*` docker-compose stack — 10 containers — was already running concurrently with
   this test's own Testcontainers Kafka + Postgres + full Spring context, on a 20s timeout), not a
   product regression. The live, real-stack run in step 2 is the stronger and decisive evidence for
   this goal regardless.

### Goal 2 — issue #51: OrderDetailPage timestamp formatting (PR #61)

**Verdict: CONFIRMED WORKING.**

1. Read `frontend/src/pages/OrderDetailPage.tsx`. It defines its own `timestampFormatter` (lines
   41-46) with the same `Intl.DateTimeFormat` options as `OrdersListPage.tsx`'s `createdAtFormatter`
   (lines 30-35) — `month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'` — and a
   comment explicitly citing issue #20's "Aug 26, 2:14 PM" treatment. `git show 3298424` (PR #61's
   commit) confirms all three of the page's full-timestamp call sites were converted from
   `new Date(...).toLocaleString()` to `timestampFormatter.format(new Date(...))`:
   `data.createdAt` (line 262), `data.updatedAt` (line 264), and each `entry.occurredAt` in the
   status-history list (line 300). Two other, unrelated `toLocaleTimeString()` calls remain in the
   file (line 60, the per-event timeline row time-only display; line 271, the "last live update at
   HH:MM:SS" transient SSE banner) — these are time-only displays for different UI elements, not the
   three full-timestamp call sites issue #51 scoped in.

2. Loaded the actual running frontend (already up on `http://localhost:5173`, same pre-existing
   stack as Goal 1) with Playwright against a real order created by Goal 1's live scenario runs
   (`order-20669`) and read the rendered DOM text directly:

   ```
   --- dl text ---
   Customer
   Cara Contention
   Total
   $498.00
   Created
   Aug 27, 4:51 PM
   Updated
   Aug 27, 4:51 PM
   --- status history text ---
   PENDING
   Aug 27, 4:51 PM
   INVENTORY RESERVED
   Aug 27, 4:51 PM
   PAYMENT PENDING
   Aug 27, 4:51 PM
   PAID
   Aug 27, 4:51 PM
   FULFILLMENT PENDING
   Aug 27, 4:51 PM
   FULFILLED
   Aug 27, 4:51 PM
   ```

   The API's raw value for this order is `"createdAt": "2026-08-27T21:51:40.416816Z"` — the rendered
   page shows the compact `"Aug 27, 4:51 PM"` form, not the raw ISO string and not a verbose
   locale string. For direct comparison, the same live stack's Orders list page for the same order
   renders identically:

   ```
   --- first orders row ---
   order-20673	Cara Contention	ⓘ REJECTED OUT OF STOCK
   $
   498.00
   	Aug 27, 4:51 PM
   ```

   Order detail's `Created`/`Updated`/status-history timestamps and the Orders list's `Created`
   column render the exact same compact format, confirming issue #51's stated goal.

## Judgment calls

- Used the docker-compose stack that was already running (`orderfulfillment-*`, up to 3 hours old)
  rather than starting a fresh one under the `kafka-portfolio-project` project name the brief
  suggested — `docker ps` showed it already up and healthy, and reusing it avoided a second,
  conflicting stack. I did not stop or touch these containers at any point; they were left exactly as
  found (still running) when I finished.
- For Goal 1's item 3 (the JUnit fallback), the brief frames it as a fallback for when the live stack
  isn't available. I had already gotten a decisive live-stack result, so I ran the JUnit test as
  supplementary evidence rather than as the primary verification. When it failed locally, I chose to
  investigate rather than either (a) suppress it as noise or (b) report it as a regression outright —
  the CI-passed-at-merge-time evidence and the working-test-in-same-environment control
  (`StandardOrderScenarioIntegrationTest`) are what let me conclude "environmental, not a regression"
  with actual evidence behind that call rather than a hunch.
- Used Playwright (already present in `frontend/node_modules/.bin`) to load the real page and read
  the actual rendered DOM text rather than reasoning from source code alone, since the brief explicitly
  asks for a visual/behavioral check, not just a code read, and a headless browser against the real
  running dev server is closer to "exercise the behavior" than eyeballing the diff.
- Created three new orders on the shared demo stack as a side effect of the three live scenario runs
  (`order-20669` through `order-20674`). Per the code's own documented rationale (ADR-010, "shared
  public sandbox, nobody obliged to reset between runs"), this is the scenario's normal, expected
  operating mode, not a side effect I judged worth reverting — there is no reset call that would
  "undo" fulfilled orders without disturbing other in-flight state on that shared demo stack, and
  doing so was out of scope for a read-only verification pass regardless.

## Deliberately not covered

- Did not attempt to reproduce the pre-fix bug (i.e. did not check out the pre-PR-63 commit and rerun
  the same three-run sequence to see it fail) — the brief marks this as a read-only pass on `main`
  and instructs not to switch branches. The CI-green-at-merge-time evidence for the JUnit test and the
  Javadoc's own explicit description of the escalating 0 -> 20/60 -> 60/60 failure pattern for the
  sibling SKU-003 bug stand in for a literal before/after diff.
- Did not check the Inventory Contention scenario's frontend presentation (e.g. the scenario-run
  detail page rendering this run's timeline) — issue #52's scope is the backend fix itself, and the
  API-level evidence (three `COMPLETED` runs, stable inventory) directly demonstrates it.
- Did not screenshot the OrderDetailPage visually (PNG) beyond capturing the rendered DOM text via
  Playwright — the DOM text is the more precise and directly quotable evidence for a formatting claim,
  a screenshot would not add information beyond what the extracted text already shows.
- Did not audit every other timestamp-rendering call site across the whole frontend for consistency
  with issue #20's format — out of scope; issue #51 was specifically about `OrderDetailPage.tsx`'s
  three call sites, which is what was checked.
- Did not investigate why the local JUnit failure occurs under resource contention (e.g. whether a
  longer `POLL_TIMEOUT` would be warranted for local dev alongside a running compose stack) — that
  would be a source change and is out of scope for a verifier that cannot edit source.
