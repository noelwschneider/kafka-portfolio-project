# Issues #18 and #19 — ScenarioRunDetailPage consolidation and display fixes

## What changed

- `frontend/src/pages/ScenarioRunDetailPage.tsx`
  - Added `DEMONSTRATES_MATCHERS`, a lookup table covering the full, fixed `demonstrates`
    vocabulary produced by `ScenarioCatalog.java` across all 8 scenarios (28 phrases), each mapped
    to a matcher function that finds the specific timeline entry it's evidenced by (e.g. `"REST
    request"` → the `POST /api/orders` HTTP entry, `"compensation"`/`"inventory release"` → the
    `InventoryReleased` event entry, `"state transitions"` → the first `STATE_CHANGE` entry).
    Phrases that describe the whole run rather than one step (`"asynchronous workflow"`, `"Kafka
    durability"`, `"consumer groups"`, etc.) have no matcher and intentionally fall through.
  - Added `matchDemonstratesPoint` / `findEntry` helpers and, in the component body, a
    `demonstratesByEntrySequence` map plus `unmatchedDemonstrates` array built by running every
    `scenarioDefinition.demonstrates` point through the matchers against the actual `data.timeline`
    for the current run.
  - Removed the static `.scenario-context-demonstrates` bullet list; replaced it with a smaller
    `.scenario-context-demonstrates-framing` paragraph rendering only the unmatched points.
  - `TimelineEntryDetail` now takes a `demonstrates: string[]` prop and renders it as an inline
    `.timeline-demonstrates` tag list inside `.timeline-main`, under the headline/raw kind-label row.
  - Removed the click-to-expand gate: `TimelineEntryDetail` no longer has `expanded` state or an
    `onClick` handler; the `<dl className="timeline-detail">` block now renders whenever
    `hasDetail` is true, unconditionally.
  - Added `displayRunId` (`runId` with a leading `run-` stripped, display-only) and changed the
    `<h1>` to use it instead of the raw `runId` — `"Scenario run #227"` instead of `"Scenario run
    run-227"`. The `runId` prop, route, and all API calls (`getScenarioRun(runId)`,
    `scenarioRunStreamUrl(runId)`, etc.) are untouched.
  - Added a `title` attribute to `<dt>Correlation ID</dt>` explaining what the id is and why it's
    shown, matching the tooltip pattern in `StatusBadge.tsx`'s `STATUS_TITLE`.

- `frontend/src/index.css`
  - Removed `cursor: pointer` and the `:hover` background rule from `.timeline-row` (no longer
    clickable) and changed `align-items` to `flex-start` so multi-line cards (headline + raw +
    demonstrates tags) align at the top instead of centering.
  - Added `.timeline-demonstrates` / `.timeline-demonstrates li` — a small inline pill-tag list
    reusing the existing `--expected` / `--expected-bg` palette variables.
  - Renamed `.scenario-context-demonstrates` (bulleted list styling, no longer used) to
    `.scenario-context-demonstrates-framing` (plain paragraph styling for the leftover-points note).
  - Left `.timeline-expand` and `.timeline-detail`/`.timeline-detail-row` untouched — `.timeline-expand`
    is still used by `OrderDetailPage.tsx`'s own (unrelated) collapse/expand timeline, which was not
    touched.

## How this was verified

TypeScript build (includes `tsc` typecheck, catches the prop signature / unused-state changes):

```
$ cd frontend && npm run build
...
✓ built in 1.85s
```

Rebuilt and force-recreated the frontend container against the real `docker compose` stack (which
was already running from before this session, shared with other concurrent agent work on other
pages):

```
$ docker compose up --build -d frontend
...
 Image kafka-portfolio-project-frontend Built
$ docker inspect orderfulfillment-frontend --format '{{.Image}}'
sha256:a93a8e5f...   # stale — compose didn't recreate the container despite building the new image
$ docker compose up -d --force-recreate frontend
 Container orderfulfillment-frontend Started
$ docker inspect orderfulfillment-frontend --format '{{.Image}}'
sha256:4b162bb6be754a0ccf826c6c7954f50054f12330b7d136844d4a2e94178e2a38   # matches the freshly built image
```

Confirmed the new code actually shipped in the served bundle:

```
$ JS=$(curl -s http://localhost:5173/ | grep -o 'assets/index-[A-Za-z0-9_-]*\.js' | head -1)
$ curl -s "http://localhost:5173/$JS" -o /tmp/idx.js
$ grep -o "timeline-demonstrates" /tmp/idx.js | head -1
timeline-demonstrates
$ grep -o "scenario-context-demonstrates-framing" /tmp/idx.js | head -1
scenario-context-demonstrates-framing
$ grep -o "The id used to trace this run" /tmp/idx.js | head -1
The id used to trace this run
$ grep -o '.\{80\}Scenario run .\{80\}' /tmp/idx.js
(0,j.jsxs)(`div`,{className:`page-header`,children:[(0,j.jsxs)(`h1`,{children:[`Scenario run `,b]}),...
```
(the heading interpolates a computed variable `b`, not the raw `runId` prop directly — matches the
`displayRunId` change; minification makes an exact source-string search for the `run-` prefix strip
unreliable, so this is the strongest bundle-level check available without a browser.)

Triggered a real `standard-order` scenario run against the live stack and fetched its full timeline
from the actual Scenario Service API (not the frontend) to validate the matcher logic against real
data:

```
$ curl -s -X POST http://localhost:8085/demo/scenarios/standard-order
{"id":"run-228","scenarioName":"standard-order","status":"RUNNING",...}
$ curl -s http://localhost:8085/demo/scenario-runs/run-228 | python3 -m json.tool
{
  "id": "run-228", "status": "COMPLETED", "orderId": "order-20083", ...
  "timeline": [
    {"sequence":1,"kind":"HTTP","label":"PUT /demo/payment-behavior","detail":{"statusCode":200}},
    {"sequence":2,"kind":"HTTP","label":"POST /api/orders","detail":{"orderId":"order-20083","statusCode":201}},
    {"sequence":3,"kind":"STATE_CHANGE","label":"Order INVENTORY_RESERVED",...},
    {"sequence":4,"kind":"STATE_CHANGE","label":"Order PAYMENT_PENDING",...},
    {"sequence":5,"kind":"STATE_CHANGE","label":"Order PAID",...},
    {"sequence":6,"kind":"STATE_CHANGE","label":"Order FULFILLMENT_PENDING",...},
    {"sequence":7,"kind":"STATE_CHANGE","label":"Order FULFILLED",...}
  ]
}
```

Working through `standard-order`'s `demonstrates` list (`["REST request","persistence","event
publication","Kafka consumption","asynchronous workflow","state transitions"]`) by hand against this
real timeline: `"REST request"` → entry 2 (`POST /api/orders`); `"state transitions"` → entry 3
(first `STATE_CHANGE`); `"persistence"` has no matching entry because this run produced no `EVENT`-kind
entries at all — the real backend for this scenario only emits `HTTP` and `STATE_CHANGE` kinds, never
`EVENT`, confirmed against a second real run (`run-227`, `payment-failure`, same absence of `EVENT`
entries) — so `"persistence"`, `"event publication"`, `"Kafka consumption"`, and `"asynchronous
workflow"` all correctly fall through to the framing note rather than being forced onto an entry that
doesn't evidence them. This is the exact "keep it as a framing note" behavior the task description
calls out as acceptable.

Confirmed the whole stack was healthy end-to-end after the rebuild:

```
$ docker compose ps --format "{{.Name}}: {{.Status}}"
orderfulfillment-frontend: Up 58 seconds
orderfulfillment-fulfillment-service: Up 2 minutes (healthy)
orderfulfillment-inventory-service: Up 2 minutes (healthy)
orderfulfillment-order-service: Up 2 minutes (healthy)
orderfulfillment-payment-service: Up 2 minutes (healthy)
orderfulfillment-scenario-service: Up About a minute (healthy)
```

## Judgment calls

- **`demonstrates`→entry mapping is a fixed lookup table, not a generic keyword heuristic.** The
  task allowed either approach. Because the full `demonstrates` vocabulary is small (28 phrases) and
  fixed in `ScenarioCatalog.java` (frozen at the Java source, not user input), an explicit per-phrase
  matcher is both more accurate and easier to audit than fuzzy keyword matching (e.g. a naive
  substring match on `"event"` would wrongly match `"asynchronous workflow"`-style whole-run phrases
  to individual entries). Anything outside this vocabulary — e.g. a hypothetical future 9th
  scenario's new phrase — falls through cleanly to the framing note rather than crashing or
  guessing, since `DEMONSTRATES_MATCHERS[point]` is `undefined` for unknown phrases.
- **Real timeline data never contains `kind: "EVENT"` entries** in the version of scenario-service
  currently running (verified above against two real completed runs). That means `"event
  publication"`, `"Kafka consumption"`, `"event IDs"`, `"offsets"`, `"idempotent consumers"`, and
  `"duplicate detection"` will currently always land in the framing note for every scenario, never on
  a card, even though matchers for them exist. I left those matchers in rather than deleting them:
  they're correct against the documented `ScenarioTimelineEntry` contract
  (`docs/openapi/scenario-service.yaml`, `TimelineEntryKind: HTTP | EVENT | STATE_CHANGE`), and if
  the backend ever starts emitting `EVENT` entries for these scenarios the frontend will pick them up
  automatically with no further change. This is a backend-emission gap, not a frontend bug — flagged
  under Deliberately not covered rather than worked around by forcing a false match onto an HTTP or
  STATE_CHANGE entry.
- **`"eventual state correction"` matches the *last* `STATE_CHANGE` entry**, not a specific named
  status, since the correcting status differs by scenario context (`PAYMENT_FAILED` for
  payment-failure, etc.) and "the last state the order reached" is a reasonable, defensible reading
  of "eventual."
- **Removed `.timeline-row`'s hover/pointer styling** since the row is no longer clickable — left the
  row itself otherwise structurally the same so `.timeline-entry`/`.timeline-row` base classes shared
  conceptually with `OrderDetailPage.tsx` stay recognizable, even though that page defines its own
  JSX and wasn't touched.
- **Did not memoize `demonstratesByEntrySequence`** (no `useMemo`) — it's O(28 × timeline length)
  per render on a page that isn't performance-sensitive (a single scenario run's timeline, rendered
  well under 100 entries even for `high-volume`), so added complexity wasn't justified.

## Deliberately not covered

- Did not visually inspect the rendered page in a real browser (no browser/screenshot tool available
  in this environment) — verification is via `tsc`/build success, direct inspection of the shipped
  JS bundle for the new class names and strings, and hand-tracing the matcher logic against real API
  responses from a live run. A human should do one visual pass to confirm the tag styling and card
  spacing look right before this ships to the README demo recording.
- Did not trigger `out-of-stock`, `consumer-outage`, `poison-message`, `inventory-contention`, or
  `high-volume` runs to confirm their specific `demonstrates` phrases attach correctly — only
  `standard-order` (fresh run) and `payment-failure` (pre-existing run) were checked against real
  data. The matcher table was built directly from `ScenarioCatalog.java`'s exact phrase list for all
  8 scenarios, but the other 5 scenarios' actual runtime timelines weren't independently confirmed to
  produce the expected labels (e.g. that `duplicate-event` really emits two `EVENT` entries with the
  same label for the `"idempotent consumers"` matcher to catch — moot for now per the `EVENT`-kind
  gap noted above, but worth checking once/if the backend starts emitting `EVENT` entries).
- Did not change `frontend/src/lib/scenarioNarrative.ts` or the expected-vs-actual match indicator,
  per the stated scope boundaries.
- Left the `EVENT`-kind emission gap (scenario-service never records `HTTP`/`STATE_CHANGE`-only
  timelines, no `EVENT` entries observed for any run checked) as a backend observation, not a fix —
  out of scope for this frontend-only task and not requested.
