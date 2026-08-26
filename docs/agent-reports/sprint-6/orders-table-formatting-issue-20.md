# Orders table visual polish (issue #20) — Total/Created formatting

## What changed

- `frontend/src/pages/OrdersListPage.tsx` — added a module-level `createdAtFormatter` (`Intl.DateTimeFormat` with `month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'`) and used it for the Created column instead of `toLocaleString()`. Restructured the Total cell markup into `<span className="order-total-value"><span className="order-total-currency">$</span><span className="order-total-amount">{...}</span></span>` so the currency symbol and digits can be styled/positioned independently.
- `frontend/src/index.css` — replaced the old `.order-total-cell { font-variant-numeric: tabular-nums; }` rule with `.order-total-cell { text-align: right; }` plus three new rules: `.order-total-value` (`display: inline-flex; width: 100%;` — fills the table column's full auto-computed width so the currency symbol lands at the same left offset on every row), `.order-total-currency` (small right margin), and `.order-total-amount` (`margin-left: auto; font-variant-numeric: tabular-nums;` — pushes digits to the right edge, keeps tabular figures for column alignment across rows).

Row-clickability/hover (`.order-row` in `index.css:123-129`) and all other columns (Order/Customer/Status) and sorting were left untouched, per scope.

## How this was verified

TypeScript check and production build both pass:

```
$ cd frontend && npx tsc --noEmit -p .
(no output — success)

$ npm run build
...
✓ built in 671ms
```

`oxlint` clean:

```
$ npm run lint
> frontend@0.0.0 lint
> oxlint
(no findings, exit 0)
```

Visual verification: no dev/preview server was already running, and this is a pure CSS/formatting change isolated to two `<td>`s, so rather than standing up the full app I built a standalone HTML harness that loads the actual `frontend/src/index.css` and renders three `orders-table` rows reproducing the exact new markup (3-digit, 7-digit, and another 3-digit total; three different `Intl.DateTimeFormat`-shaped date strings), then screenshotted it with Playwright (`chromium`, already installed in `frontend/node_modules`, v1.49.1):

```
$ node _shot_tmp.mjs harness.html shot.png   # harness + script written to scratchpad, deleted after
done
```

Screenshot confirmed:
- `$` sits at the identical horizontal position on all three rows (`12.50`, `10345.99`, `7.00`) — the widest total (`10345.99`) doesn't push its `$` further right than the narrower ones, and digits are right-aligned so the decimal points line up.
- Created column renders as `Aug 26, 2:14 PM`, `Jul 4, 11:59 AM`, `Jan 1, 12:00 AM` — no year, no seconds, consistent with the requested format.

Harness and screenshot files were scratch-only (`/private/tmp/claude-501/.../scratchpad/`) and have been removed; no files were added under `frontend/`.

## Judgment calls

- **Formatter placement**: added `createdAtFormatter` as a local `Intl.DateTimeFormat` constant inside `OrdersListPage.tsx` rather than extracting a shared `frontend/src/lib/date.ts` utility. `OrderDetailPage.tsx` has three other `toLocaleString()` call sites (`createdAt`, `updatedAt`, `entry.occurredAt`) that could benefit from the same treatment, but the task scope explicitly said "don't touch other columns" and named only `OrdersListPage.tsx`; introducing a shared util would mean either leaving it half-adopted (used only by one page) or reaching into `OrderDetailPage.tsx`, which is out of scope. Left as a local constant; a follow-up could promote it to shared once/if `OrderDetailPage.tsx`'s timestamps get the same polish pass.
- **`Intl.DateTimeFormat` field choice**: `hour: 'numeric', minute: '2-digit'` produces `2:14 PM` (no leading zero on hour, matching the issue's own example) rather than `hour: '2-digit'` which would give `02:14 PM`. Chose the former to match the example format given in the task exactly.
- **Right-alignment technique**: considered a fixed `em`-width column (e.g. `width: 5.5em`) as a simpler alternative, but rejected it — an arbitrary fixed width is brittle against currency values exceeding that width (would clip or wrap) and doesn't scale with actual data. Used `width: 100%` on an inline-flex wrapper instead, which rides the table's normal auto-layout column-width computation, so it stays correct for whatever the widest total in a given render actually is, with no magic number to maintain.
- **Verification method**: no app server (backend or frontend dev/preview) was running at task start and this change touches only static markup/CSS with no data-fetching or interaction logic, so a full `docker compose up` felt disproportionate. A static Playwright-rendered HTML harness against the real `index.css` file gave a real rendered screenshot of the actual CSS rules without spinning up unrelated services, consistent with "rebuild only what your change touches."

## Deliberately not covered

- Did not verify against live order data via `docker compose` (no backend/order-service was exercised) — the harness used synthetic rows built from the exact JSX/CSS classes shipped, not a live fetch through `listOrders`. Given the change is presentation-only (no data shape, API, or state logic touched), this was judged sufficient, but it means real API response formatting (e.g. actual `totalAmount` precision/rounding from the backend) was not exercised end-to-end.
- Did not check the Created column format against locales other than the environment default (`Intl.DateTimeFormat(undefined, ...)` uses the runtime's locale) — e.g. non-US date orderings weren't visually spot-checked. This matches existing behavior elsewhere in the app (`toLocaleString()` calls in `OrderDetailPage.tsx` are similarly locale-dependent), so no new inconsistency was introduced, but it also wasn't explicitly tested.
- Did not touch or re-verify `OrderDetailPage.tsx`'s three `toLocaleString()` timestamps — out of scope per the task, noted above as a possible follow-up.
- Did not add/modify any automated frontend tests (none exist for this component currently — `npm run lint`/`tsc`/`build` are the only automated checks available in `frontend/package.json`).
