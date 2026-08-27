# Orders pagination/filtering/lookup, OrderDetailPage timestamps, Overview no-data hint (#33, #21, #51, #58)

## What changed

- `frontend/src/api/orders.ts` — `listOrders()` now takes a `ListOrdersParams` object (`page`, `size`, `status`, `customerId`) and builds the query string accordingly, defaulting to `page=0&size=20` (was a hardcoded `?size=50` with no page/filter support).
- `frontend/src/pages/OrdersListPage.tsx` — added `page` state and pagination controls (Previous/Next, "Page X of Y · N orders") driven by `OrderPage.page`/`totalPages`/`totalElements`; added `statusFilter`/`customerIdFilter` state wired into the same `listOrders` query (resetting `page` to 0 on any filter change); added an order-id lookup form that calls `onSelectOrder` on submit, reusing the existing `/orders/:orderId` route; split the empty-state message so "No orders match these filters" shows only when a filter is active, and "No orders yet" (with the create-order CTA) only shows with no filters active.
- `frontend/src/index.css` — added `.pagination`/`.pagination-status` and `.orders-toolbar`/`.orders-filters`/`.order-id-lookup` rules for the new controls, matching the existing button/input/select styling already used elsewhere on the page.
- `frontend/src/pages/OrderDetailPage.tsx` — added a module-level `timestampFormatter` (same `Intl.DateTimeFormat` config as `OrdersListPage.tsx`'s `createdAtFormatter` from issue #20) and used it for `createdAt`, `updatedAt`, and each `statusHistory` entry's `occurredAt`, replacing the three `toLocaleString()` call sites.
- `frontend/src/pages/OverviewPage.tsx` — added a `hasNoDataComponent` boolean (`kafka.state === 'no data' || db.state === 'no data'`) and wrapped the `"No data" means none has reported yet...` hint `<p>` in that condition instead of always rendering it.

## How this was verified

Static checks, all clean on the final branch state:

```
$ cd frontend && npx tsc --noEmit -p .
(no output)

$ npm run lint
> frontend@0.0.0 lint
> oxlint
(no findings, exit 0)

$ npm run build
...
✓ built in 3.10s
```

Live/behavioral verification hit severe concurrent-agent resource contention on the shared dev host (see Judgment calls) — `docker compose`'s `order-service`/`kafka`/`inventory-service`/`scenario-service`/`fulfillment-service` containers were repeatedly OOM-killed (exit 137) by another agent's runaway full-stack rebuild while this task was in progress, matching the exact failure mode `docs/planning/engineering-rules.md`/`CLAUDE.md` warn about from Sprint 4. Rather than keep fighting a stack that kept dying out from under me, I built the frontend's production bundle (`npm run build`, output above) and served it standalone with `vite preview` on port 4173, then drove the *real* bundle with Playwright (`chromium`, already installed in `frontend/node_modules`, v1.49.1) — first against whatever backend was reachable, then with `page.route()` intercepting `/api/orders*`, `/api/orders/{id}`, `/actuator/health` to supply controlled responses so every behavior could be exercised end-to-end through the actual shipped code, not a synthetic harness:

Pagination + filters, against the live (if flaky) order-service while it was briefly reachable:
```
--- After initial load, orders requests: [ 'http://localhost:8081/api/orders?page=0&size=20' ]
--- After status filter select, orders requests: [ 'http://localhost:8081/api/orders?page=0&size=20&status=PENDING' ]
--- After customerId filter typed, orders requests: [
  'http://localhost:8081/api/orders?page=0&size=20&status=PENDING&customerId=demo-customer'
]
```

Pagination paging to a second, different page of content, with mocked `/api/orders` (two-page fixture, `totalPages: 2`):
```
Page 1 order ids: [ 'order-00002', 'order-00001' ]
Pagination status page 1: Page 1 of 2 · 3 orders
Page 2 order ids: [ 'order-00099' ]
Pagination status page 2: Page 2 of 2 · 3 orders
All /api/orders request query strings seen: [ '?page=0&size=20', '?page=1&size=20' ]
```

Order-id lookup navigation:
```
URL after order-id lookup submit: http://localhost:4173/orders/order-00042
```

OrderDetailPage timestamp formatting (mocked `GET /api/orders/order-77123`, `createdAt: 2026-08-26T14:14:00.000Z`, `updatedAt: 2026-08-26T15:30:00.000Z`):
```
OrderDetail dl values (Customer, Total, Created, Updated): [ 'demo-customer', '$258.00', 'Aug 26, 9:14 AM', 'Aug 26, 10:30 AM' ]
Status history timestamps: [ 'PENDING', 'Aug 26, 9:14 AM', 'FULFILLED', 'Aug 26, 9:20 AM' ]
```
Matches `docs/agent-reports/sprint-6/orders-table-formatting-issue-20.md`'s format exactly ("Aug 26, 2:14 PM" shape — short month, numeric day, no leading zero on hour, no year, no seconds).

Overview no-data hint, mocked `/actuator/health` so `order-service` reports both/neither of `kafka`/`db` components:
```
Case A (kafka+db both no data) -> hint visible count: 1
Case A status rows: [ 'Healthy', 'Healthy', 'Healthy', 'Healthy', 'Healthy', 'no data', 'no data' ]
Case B (kafka+db both UP) -> hint visible count: 0
Case B status rows: [ 'Healthy', 'Healthy', 'Healthy', 'Healthy', 'Healthy', 'UP (via Order Service)', 'UP (via Order Service)' ]
```

CI on the PR (GitHub Actions, unaffected by the local host contention):
```
$ gh pr checks 61
Required checks	pass	4s
frontend	pass	14s
fulfillment-service	skipping	0
inventory-service	skipping	0
order-service	skipping	0
payment-service	skipping	0
scenario-service	skipping	0
changes	pass	7s
```
(Backend jobs correctly skip via the repo's per-service path filters, since this PR only touches `frontend/`.)

Playwright driver scripts were written to the scratchpad and to a temporary `frontend/_verify_*.mjs` (needed local `node_modules` resolution), and were deleted after use — `git status --short frontend/` was clean of them before committing.

## Judgment calls

- **Order-id lookup UX**: the issue asked for "an order-ID input that navigates straight to the existing order-detail-by-id route" and "a customer-ID lookup wired to the same `customerId` param." I read the customer-ID lookup as the *same* input as the customer-ID filter (there is no separate customer-detail route to navigate to — filtering the list *is* the lookup), rather than building a second, redundant customer-ID field. The order-ID field is genuinely separate since it navigates rather than filters.
- **Page reset on filter change**: changing `statusFilter` or `customerIdFilter` resets `page` to 0. Not explicitly specified, but leaving `page` unchanged risked landing on an out-of-range page for the new filtered result set (e.g. filtered down to 1 page while sitting on page 3).
- **Empty-state wording split**: split "No orders yet" (unfiltered, with the create-order CTA) from "No orders match these filters" (filtered, no CTA — placing an order doesn't fix a filter mismatch). Not explicitly requested, but the original unconditional "No orders yet. Place the first order" text is actively misleading once filters exist and can legitimately produce a zero-result page against a non-empty order set.
- **PAGE_SIZE constant**: kept the UI's page size at 20 (`docs/openapi/order-service.yaml`'s documented default), rather than reusing the old `size=50`. No count/perf concern was named in the issue, so I matched the contract's own default rather than picking a number.
- **Environment**: mid-task, `docker compose`'s backend containers (`order-service`, `kafka`, `inventory-service`, `scenario-service`, `fulfillment-service`) were repeatedly OOM-killed by what `docker stats` and interleaved build log output showed was a concurrent agent's full, un-scoped `docker compose up --build` running on the same shared host — visible as unrelated containers (`zen_pike`, `gallant_wilbur`, a `testcontainers-ryuk` sidecar) at high CPU, and as Maven/Docker BuildKit bake output for `payment-service`/`fulfillment-service`/`scenario-service` interleaved into a build I had scoped to `--build frontend` only. I did not run an un-scoped rebuild myself; the scoped `frontend` build request appears to have been merged into another agent's already-in-flight bake session by Docker Compose/BuildKit. Rather than keep retrying a doomed live-stack path (which would have contributed further load to an already-thrashing host), I switched to the `vite preview` + Playwright route-interception approach described above, per this task's own guidance that a local preview against a reachable backend (or mocked responses, in this case) is an acceptable substitute when a full rebuild isn't warranted.
- **Shared-workdir branch contamination**: partway through, `git status`/`git log` showed the working directory's checked-out branch and HEAD had been switched out from under me by what must have been another concurrent agent operating in the same repo clone (my first commit landed on `docs/frontend-styling-contract`, a branch I never checked out; a stray doc commit from that same agent later appeared on my own branch). I recovered by: saving my diff as a safety patch, `git reset --mixed HEAD~1` to undo the misplaced commit without touching the other agent's *uncommitted* changes, `git stash push` with explicit pathspecs to move only my files, and finally rebuilding my branch from a clean `main` merge-base via `git cherry-pick` of just my four commits (verified via `git diff main <branch> --stat` showing only the five files I actually touched) before force-pushing with `--force-with-lease`. At no point did I run a destructive command (`--hard` reset, `clean`, or anything touching the other agent's uncommitted files) against changes I didn't own. I did not attempt to fix or report on the other agent's own misplaced commit beyond removing it from my branch — that's their branch's problem, not mine to resolve.
- I did not shut down or otherwise touch `docker compose` services beyond the minimal, non-destructive recovery attempts noted above (`docker compose up -d kafka`, `up -d order-service`, both plain restarts of already-built images, no rebuild) — the containers were already in a degraded state caused by another agent's activity before I intervened, and further stopping/removing containers risked interfering with that agent's own in-flight work. The stack was left in whatever state the concurrent contention left it in; I did not run `docker compose down`.

## Deliberately not covered

- Did not get a stable, fully-live `docker compose` pass (real backend, real Postgres-backed data, real multi-page dataset) for any of the four changes, for the resource-contention reasons above. The mocked-route Playwright verification exercises the actual shipped bundle end-to-end (real DOM, real fetch calls, real React Query cache behavior) but the JSON payloads themselves are hand-built fixtures, not database-backed responses.
- Did not verify sort behavior interacting with pagination (e.g. does client-side sort still make sense once only one page of results is in memory) — `sortOrders` already only ever sorted the current page's `content` before this change (issue was scoped to add pagination itself, not to make sorting page-aware), so this is pre-existing behavior, not a regression, but it's worth flagging: with pagination now real, a user sorting by "Created" only reorders the current page, not the whole result set. Not in scope per the issue text (which asked for pagination and filtering, not a sort redesign), but a reasonable follow-up.
- Did not add automated frontend tests — none exist for this component today (`tsc`/`oxlint`/`vite build` are the project's only automated frontend checks, per `frontend/package.json`).
- Did not verify locale-dependent rendering of the new timestamp formatter beyond the runtime default locale, consistent with how issue #20's original formatter was verified.
- Did not investigate or attempt to fix the concurrent-agent resource contention itself (the OOM-killed containers, the merged Docker build, the branch-switching contamination) — flagged here and in the PR context as an observed incident, not remediated, since fixing shared-host/orchestration behavior is outside this task's scope.
