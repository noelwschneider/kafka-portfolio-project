# Issues #15, #16, #17 — OverviewPage hero, System Status hint, Scenario card polish

## What changed

- `frontend/src/pages/OverviewPage.tsx` — removed the `overview-hero` block (H1 "Order
  Fulfillment Systems Lab" + lede paragraph); trimmed the System Status hint to just the
  "no data" explanation; replaced the Scenarios hint with "See how the system handles a variety
  of scenarios."; removed the `<details>/<summary>Details</summary>` "Demonstrates" disclosure
  and the "Expected terminal state: ..." line from each scenario card; added `className="button-primary"`
  to the Run Scenario button; updated the stale top-of-file comment that referenced "the note
  rendered on the page itself" (no longer accurate after the hint was trimmed) to reference the
  actual Actuator config key instead.
- `frontend/src/index.css` — removed the now-unused `.overview-hero` / `.overview-lede` rules;
  removed the now-unused `.scenario-card ul`, `.scenario-card-expected`, `.scenario-card-details
  summary`, `.scenario-card-details h4` rules; redesigned `.scenario-card` (purple `--accent`
  top border accent, `--bg-alt` background instead of flat white/dark, subtle box-shadow, hover
  lift + accent-tinted shadow); fixed a pre-existing selector bug (`.scenario-card h2` never
  matched — the JSX renders `<h3>` — so the title font-size/margin rule was silently dead; changed
  to `.scenario-card h3`); added `.button-primary` (accent-purple fill, white text, darker-purple
  hover, muted-disabled state) so Run Scenario is visually the primary action, distinct from the
  page's other buttons which keep the default `button` styling.
- `services/order-service/src/main/resources/application.yml`,
  `services/inventory-service/src/main/resources/application.yml`,
  `services/payment-service/src/main/resources/application.yml`,
  `services/fulfillment-service/src/main/resources/application.yml`,
  `services/scenario-service/src/main/resources/application.yml` — added
  `management.endpoint.health.show-components: always` under the existing `management.endpoint.health`
  block in each, so `/actuator/health` returns a `components` map instead of just `{"status":"UP"}`.

## Actuator investigation (#16 part 2)

Confirmed via `curl` against the running `docker compose` stack before changing anything: all five
services returned `{"status":"UP"}` with no `components` key at all — `deriveInfraStatus()` in
`OverviewPage.tsx` had nothing to read regardless of Kafka/PostgreSQL reachability. This matches
Spring Boot's default (`show-components: never` unless authenticated), confirmed by the project's
pinned `spring-boot-starter-parent` version 4.1.0 in the root `pom.xml`.

Adding `management.endpoint.health.show-components: always` to each service was a one-line config
change per service, no application logic touched, as scoped. After rebuilding and restarting all
five containers, every service now reports a `db` component (`"db":{"status":"UP"}`) — confirmed
by `curl` below.

**`kafka` still does not appear**, and this is not something the config key can fix: Spring Boot's
`KafkaHealthIndicator` auto-configuration was removed from the framework (confirmed by `unzip -l`
on the built order-service jar — no `KafkaHealthIndicator` class on the classpath at all, and by
web search: Spring Boot no longer ships a Kafka health indicator out of the box; getting one
requires either a custom `HealthIndicator` bean built on a `KafkaAdmin`/`AdminClient`, or a
third-party dependency). That is genuinely "something more involved" than a config key — it is new
application code — so per the task's own stop condition I did not write one. `deriveInfraStatus()`'s
"no data" fallback for `kafka` is therefore still exercised in the live UI (correctly — the row
still reads "no data" instead of claiming a status nothing actually reported), while `db` now shows
real data. `docs/CHANGELOG-contracts.md` was not touched — this isn't a contract file change, and
`/actuator/health`'s shape is not one of the frozen contracts in `docs/openapi/`/`docs/events/`.

## How this was verified

Stack was already running (`docker compose ps`, started ~46 minutes before I began — not brought up
by me, left running as found):

```
$ docker compose ps --format '{{.Service}}: {{.Status}}'
frontend: Up 50 minutes
fulfillment-service: Up 51 minutes (healthy)
...
```

Before the config change, `/actuator/health` had no `components` key:

```
$ curl -s http://localhost:8081/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}
```

Rebuilt and restarted the five backend services after the `application.yml` edits:

```
$ docker compose build order-service inventory-service payment-service fulfillment-service scenario-service
...
 Image kafka-portfolio-project-inventory-service Built
 Image kafka-portfolio-project-payment-service Built
 Image kafka-portfolio-project-fulfillment-service Built
 Image kafka-portfolio-project-scenario-service Built
 Image kafka-portfolio-project-order-service Built

$ docker compose up -d order-service inventory-service payment-service fulfillment-service scenario-service
...
 Container orderfulfillment-order-service Healthy
 Container orderfulfillment-fulfillment-service Healthy
 Container orderfulfillment-payment-service Healthy
 Container orderfulfillment-inventory-service Healthy
 Container orderfulfillment-scenario-service Started
```

After: `db` component present on all five services (kafka still legitimately absent, see above):

```
$ for p in 8081 8082 8083 8084 8085; do echo "=== $p ==="; curl -s http://localhost:$p/actuator/health; echo; done
=== 8081 ===
{"components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},"groups":["liveness","readiness"],"status":"UP"}
=== 8082 === (same shape, db UP)
=== 8083 === (same shape, db UP)
=== 8084 === (same shape, db UP)
=== 8085 === (same shape, db UP, after allowing it a few seconds to finish starting)
```

Confirmed no `KafkaHealthIndicator` class ships on the runtime classpath:

```
$ docker compose exec order-service sh -c 'unzip -l app.jar | grep -i KafkaHealth'
(no output)
```

Frontend: `frontend/src/pages/OverviewPage.tsx` and part of `frontend/src/index.css` are the only
files I touched, but the full `docker compose build frontend` fails on an unrelated, pre-existing
error in `ScenarioRunDetailPage.tsx` (out of scope per the task's own boundary — already modified,
presumably by a concurrent agent, per the git status snapshot at task start: `TS6133:
'matchDemonstratesPoint' is declared but its value is never read` / `TS2741: Property
'demonstrates' is missing`). To verify my own changes without touching that file, I ran `npx vite
build` directly (skips the `tsc -b` project-wide type check that `npm run build` runs first, but
does the real esbuild/rollup bundling `npm run build` also does) — it succeeded:

```
$ npx vite build
...
✓ built in 882ms
```

Then grepped the built bundle to confirm the actual shipped output matches the source changes:

```
$ grep -o "See how the system handles a variety of scenarios\." dist/assets/*.js
dist/assets/index-DHQYr5di.js:See how the system handles a variety of scenarios.

$ grep -c "Order Fulfillment Systems Lab" dist/assets/*.js   # hero title gone
(no match)

$ grep -o "Demonstrates" dist/assets/*.js | wc -l
       0
$ grep -o "Expected terminal state" dist/assets/*.js | wc -l
       0
$ grep -o "button-primary" dist/assets/*.js | wc -l
       1

$ grep -o "button-primary[^}]*{[^}]*}" dist/assets/*.css
button-primary{background:var(--accent);border-color:var(--accent);color:#fff;font-weight:600}
button-primary:hover:not(:disabled){background:#4c31d6;border-color:#4c31d6}
button-primary:disabled{background:var(--bg-alt);border-color:var(--border);color:var(--text)}
```

Served the real build and confirmed it loads:

```
$ npx vite preview --port 5175 &
$ curl -s http://localhost:5175/ -o /dev/null -w "preview HTTP:%{http_code}\n"
preview HTTP:200
```

Confirmed the live data the page depends on is real (Scenario Service, running in the compose
stack, unaffected by the frontend build issue):

```
$ curl -s http://localhost:8085/demo/scenarios | head -c 500
[{"name":"standard-order","title":"Standard Fulfillment","description":"Creates an order with
available inventory and successful payment.","demonstrates":[...],"expectedTerminalStatus":"FULFILLED","available":true}, ...]
```

(The backend still sends `demonstrates`/`expectedTerminalStatus` per the existing `/demo/scenarios`
API contract — the frontend change simply stops rendering those two fields; no contract change was
needed or made.)

Stopped the local `vite --port 5174` dev server and `vite preview --port 5175` I started for
verification; the `docker compose` stack itself was left running exactly as I found it (was already
up before I started), with the five backend service containers now on rebuilt images.

## Judgment calls

- **#15 — dropped the hero wrapper entirely** rather than leaving an empty `<div className="overview-hero">`.
  The task left this as my call ("your call on whether an empty hero wrapper is worth keeping
  structurally"); since there was no remaining content and no other consumer of that class, keeping
  a wrapper with nothing in it just to preserve a future insertion point seemed like more clutter
  than value. Removed the now-dead `.overview-hero`/`.overview-lede` CSS rules for the same reason.
- **#16 — stopped at the config change and did not build a custom Kafka health indicator.** The
  task explicitly asked me to confirm via a real `/actuator/health` response first and stop short of
  "something more involved" if that's what it turned out to need. It did: Spring Boot no longer
  ships a Kafka health indicator, so populating that row would mean writing and testing new
  `HealthIndicator` application code (talking to `AdminClient`, deciding what "healthy" means for a
  cluster from one consumer's point of view, etc.) — a different-shaped task than "small backend
  config change." Reporting this rather than improvising a `KafkaHealthIndicator` bean under a
  frontend-polish ticket.
- **#17 — fixed the `.scenario-card h2` → `h3` selector mismatch.** This was a pre-existing bug
  (the CSS never matched the actual `<h3>` in the JSX, so the card title never got the intended
  font-size/margin treatment), discovered while working the "give it visual hierarchy" part of the
  same ticket, in the same file already in scope. Left it fixed rather than filing it separately
  since it's a one-line, directly-adjacent fix to the exact rule I was already touching.
- **#17 — verified the built bundle via `npx vite build` + `grep`/`vite preview` instead of the
  full Docker frontend image**, because the Docker build path is blocked by an unrelated,
  out-of-scope TypeScript error in a file the task explicitly told me not to touch. `vite build`
  runs the same bundler `npm run build` does; it only skips the separate `tsc -b` project-wide
  type-check step, which was failing solely because of the other file. This is not a full substitute
  for a green Docker image, but it is real evidence the changed code compiles and ships correctly,
  not just that it "should work."

## Deliberately not covered

- **`docker compose build frontend` is currently broken** on `main` (pre-existing, unrelated to this
  task) due to `frontend/src/pages/ScenarioRunDetailPage.tsx` TS errors
  (`TS6133`/`TS2741` around `matchDemonstratesPoint`/`demonstrates`). The task's scope boundary
  explicitly excludes that file. The live `frontend` container in the compose stack is therefore
  still running its pre-existing image (built before this session), not one containing today's
  `OverviewPage.tsx`/`index.css` changes — I verified those changes via a local `vite build` +
  `vite preview` instead (see above), not by hitting the actual running frontend container on
  port 5173. Whoever owns `ScenarioRunDetailPage.tsx` needs to land a fix before the frontend image
  can be rebuilt for real deployment.
- **Kafka health data on the Overview page is still "no data" and stays that way** — see the
  Actuator investigation section above. This needs a genuine follow-up ticket (custom
  `HealthIndicator` or third-party dependency) if seeded Kafka status is still wanted; it was not
  attempted here as it's out of this ticket's stated scope.
- Did not visually screenshot the page in an actual browser (no browser/screenshot tool was
  available in this session) — verification is via the built JS/CSS bundle contents, `vite preview`
  serving successfully, and the live API responses the page consumes, not a pixel-level visual check
  of hover states, spacing, or dark-mode rendering of the new `.scenario-card`/`.button-primary`
  styles.
- Did not touch `ScenarioRunDetailPage.tsx`, `OrdersListPage.tsx`, or `CreateOrderPage.tsx`, per the
  explicit scope boundary.
- Left the four backend services' `target/classes/application.yml` (build output, not source)
  untouched — those get regenerated from `src/main/resources/application.yml` on the next Maven
  build, which the Docker rebuild already exercised.
