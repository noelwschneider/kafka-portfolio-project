# Issue #9 — Remove System Health nav item and route

## What changed

- `frontend/src/App.tsx` — removed the `SystemHealthPage` import, removed the `{ to: '/health', label: 'System Health' }` entry from `NAV_ITEMS`, and changed the `/health` route from rendering `SystemHealthPage` to `<Navigate to="/" replace />` (same pattern already used for the retired `/scenarios` route from issue #5, so an old bookmark/link to `/health` still lands somewhere valid instead of 404ing).
- `frontend/src/pages/SystemHealthPage.tsx` — deleted. Confirmed via `grep -rn "SystemHealthPage" frontend/src` that nothing else imported it before deleting.

No other files were touched. `frontend/src/api/health.ts` (`fetchAllServiceHealth`) is still used by `OverviewPage.tsx` for the Home System Status table, so it was left alone.

## How this was verified

Confirmed no other references to the page or route before/after the change:

```
$ grep -rn "SystemHealthPage\|/health" frontend/src --include="*.tsx" --include="*.ts"
frontend/src/App.tsx:88:              <Route path="/health" element={<Navigate to="/" replace />} />
frontend/src/api/health.ts:2:// exposed by Spring Boot Actuator (/actuator/health, ...) ...
frontend/src/api/health.ts:48: * Fetches GET {baseUrl}/actuator/health. ...
frontend/src/api/health.ts:55:    const response = await fetch(`${baseUrl}/actuator/health`);
frontend/src/pages/OverviewPage.tsx:3:import { fetchAllServiceHealth, type ServiceHealth } from '../api/health';
frontend/src/pages/OverviewPage.tsx:129:        Statuses come from each service's real <code>/actuator/health</code> endpoint, polled every
```
(only the redirect route and the unrelated shared `api/health.ts` remain — no leftover import of the deleted page, no "System Health" nav label anywhere.)

Type-check + production build:

```
$ npm run build
> frontend@0.0.0 build
> tsc -b && vite build
...
✓ built in 414ms
```
(`tsc -b` ran first and passed with zero errors; `vite build` completed clean with only the pre-existing "chunk > 500kB" size warning, unrelated to this change.)

Lint:

```
$ npm run lint
> frontend@0.0.0 lint
> oxlint
```
(no output — no violations.)

Confirmed the "System Health" string is gone from the compiled output entirely:

```
$ grep -rl "System Health" dist/assets/*.js
(no matches)
```

Ran the dev server and hit it live:

```
$ npm run dev &   # http://localhost:5173/
$ curl -s http://localhost:5173/ | grep -o 'Order Fulfillment Systems Lab'
Order Fulfillment Systems Lab
$ curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5173/health
200
```
(Vite's dev server always serves `index.html` with 200 for any path under client-side routing — the meaningful check is that the SPA still boots and the `/health` path doesn't error; the actual `Navigate to="/" replace"` client-side redirect logic is covered by the passing `tsc -b` type-check, since `SystemHealthPage` no longer exists for the route to reference.) Dev server was stopped after the check (`pkill -f vite`) — nothing left running that wasn't already running before this session (none was).

## Judgment calls

- **Redirect vs. drop the route entirely**: chose `<Route path="/health" element={<Navigate to="/" replace />} />`, matching the exact pattern issue #5 already established for the retired `/scenarios` route in this same file. Dropping the route entirely would fall through to the catch-all `*` route, which also redirects to `/` — functionally identical — but keeping an explicit `/health` entry documents intent (this path used to exist and was deliberately retired) rather than leaving it to an undocumented catch-all. Consistency with the sibling precedent in the same file weighed more than the one extra line saved by omitting it.
- **Did not port SystemHealthPage's extra detail into OverviewPage**: per the ticket's explicit instruction, the per-service Actuator component breakdown table, the consumer-group-status disclaimer banner, and the "Recent errors" summary line in `SystemHealthPage.tsx` were not merged into Home's simpler System Status table. Flagging per the ticket's ask below rather than silently doing it.
- **Did not touch `docs/planning/sprint-4/` or other pre-existing uncommitted frontend changes** (`index.css`, `ArchitecturePage.tsx`, `OverviewPage.tsx`, deletion of `ScenariosPage.tsx`) found already sitting in the working tree at session start — these are the landed-but-uncommitted results of issues #5 and #8, explicitly out of scope per the delegation prompt.

## Deliberately not covered

- **Loss of per-component health breakdown**: `SystemHealthPage.tsx` showed, per service, the individual Actuator health-indicator components (e.g. db, kafka, diskSpace) with their own status, not just one aggregate UP/DOWN. Home's System Status table only ever showed the aggregate per-service status. Removing System Health does lose that finer-grained view with no replacement — if a service reports UP overall but one component (e.g. its DB connection pool) is degraded, that's no longer visible anywhere in the UI. This is a real, if minor, regression in diagnostic detail. Per the ticket's explicit instruction, I did not port that table into Home; flagging it here rather than expanding scope unilaterally.
- **No browser-driven interactive check** (e.g. Playwright/manual click-through) of the nav bar rendering or clicking a `/health` link to confirm the redirect fires client-side — verified instead via `tsc -b` (which would fail to compile a reference to a route element that no longer exists) and via confirming the "System Health" string is entirely absent from the built JS bundle. No frontend test harness (Vitest/RTL) exists in this repo to write an automated nav-item test against; not introduced here as it's outside this issue's scope.
- **No git commit created** — per project rules, commits happen only when explicitly requested.
