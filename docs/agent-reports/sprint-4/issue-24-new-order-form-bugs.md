# Issue #24 — New Order modal / quantity field bugs

## What changed

- `frontend/src/pages/OrdersListPage.tsx` — the `.modal-overlay` now tracks the element a
  `mousedown` landed on (`overlayMouseDownTarget`, a `useRef`) and only calls `closeCreate()` on
  `click` when both the mousedown target and the click target are the overlay itself
  (`e.target === e.currentTarget && overlayMouseDownTarget.current === e.currentTarget`). A
  click-drag that starts inside `.modal` and is released outside it no longer closes the modal,
  since the mousedown target was inside `.modal`, not the overlay.
- `frontend/src/pages/CreateOrderPage.tsx`:
  - `LineDraft.quantity` changed from `number` to `number | ''` so the input can be genuinely
    empty while being edited instead of coercing an empty string to `0`.
  - The quantity `<input>`'s `onChange` now sets `''` when the raw value is empty, and clears any
    existing validation-error flag for that line as the user types.
  - Removed the native `required` attribute from the quantity input — it was intercepting form
    submission via the browser's built-in validation UI before the app's own submit-time check
    ever ran, which would have made the chosen approach unreachable.
  - `handleSubmit` now validates before mutating: any line with a `sku` selected and an empty or
    `< 1` quantity is added to an `invalidQuantityLines` index set, submission is blocked
    (`return` before `mutation.mutate`), and the affected input(s) get an `input-invalid` class,
    `aria-invalid`, and an inline `"Enter a quantity"` message. This is the developer's stated
    preferred approach (block + visual feedback) from the ticket, option (b).
- `frontend/src/index.css` — added `.input-invalid` (red border/outline, reusing `--failure`) and
  `.field-error` (small red inline text) to support the new validation feedback.

## How this was verified

Ran `npm run build` (which is `tsc -b && vite build`) locally in `frontend/` — passed with no
type errors for either changed file (the only pre-existing failure encountered was in
`ScenarioRunDetailPage.tsx`, owned by a concurrent agent, and is not part of this change; see
"Deliberately not covered").

```
$ cd frontend && npm run build
...
✓ built in 773ms
```

Backend stack (`docker compose`) was already running before this session started (order-service,
inventory-service, payment-service, fulfillment-service, scenario-service, postgres, kafka,
prometheus, grafana, and the dockerized frontend) — left as found, not started or stopped by me.
Rebuilding the dockerized frontend image was blocked by the unrelated, currently-broken
`ScenarioRunDetailPage.tsx` in a concurrent agent's in-progress work (whole-project `tsc -b`
fails on that file), so I instead ran a local Vite dev server (`npm run dev`, port 5174) against
the already-running backend services on their default `localhost` ports (8081-8085), which are
real, non-mocked services — same application code path, just without the extra Docker image
build step for a file outside my scope.

Verified with a real Playwright browser (Chromium, downloaded via `npx playwright install`) doing
actual mouse-event sequences (`page.mouse.move/down/move/up`) against the live app, not code
reading:

```
BUG1: modal still open after drag-out = true
BUG1: customer id value preserved = drag-test-customer
BUG1-control: modal closes on genuine overlay click = true
BUG2: quantity after fill 3 = 3
BUG2: quantity after clearing = "" (expect empty string, never "0")
BUG2: available sku options count = 5
BUG2: input has invalid class after submit with empty qty = true
BUG2: inline field-error message visible = true
BUG2: modal still open (submission blocked) = true
[POST /api/orders] 201
BUG2: modal closed after valid submit = true
BUG2: submit error message = null
```

Bug 1 reproduction: opened the New Order modal, typed into the customer-id field, then did an
explicit `mousedown` at a point inside `.modal`, moved the mouse (10 interpolated steps) to a
point inside `.modal-overlay` but outside `.modal`, then `mouseup` there — i.e. exactly the
click-target-is-overlay-but-mousedown-was-inside-modal scenario from the ticket. The modal stayed
open and the typed customer id was preserved. A control check (mousedown and mouseup both on the
overlay, a genuine outside click) confirmed the overlay still closes the modal in the legitimate
case.

Bug 2 reproduction: filled the quantity input with `3`, then cleared it — `inputValue()` returned
`""`, never `"0"`. Selected a real SKU with the quantity still empty and clicked "Place order":
the request was not sent (no `POST /api/orders` logged), the input got the `input-invalid` class,
and the inline "Enter a quantity" message appeared. Modal stayed open. Filled the quantity with
`2` and submitted again: `POST /api/orders` returned `201`, and the modal closed (via
`onOrderCreated`), confirming the valid path still works end-to-end against the real
order-service/inventory-service.

Playwright and its Chromium binary were installed only into the local `node_modules`/cache for
this verification; the throwaway `verify.mjs` script used to drive it was deleted afterward and
never committed (`git status --short` in `frontend/` shows no trace of it). The local Vite dev
server (port 5174) was stopped after verification; the pre-existing `docker compose` stack was
left running exactly as found.

## Judgment calls

- **Chose option (b) (block + visual feedback) over (a) (default to 1).** The ticket named (b) as
  the developer's preferred, more graceful approach and explicitly allowed (a) as a fallback only
  if (b) added meaningfully more complexity. Implementing (b) required one extra piece of state
  (`invalidQuantityLines`) and a few lines in `handleSubmit`/the input's `onChange` — not enough
  complexity to justify the fallback.
- **Removed the quantity input's `required` attribute.** Not explicitly called out in the ticket,
  but necessary: with `required` still present, the browser's native "please fill out this field"
  validation UI intercepts form submission before React's `onSubmit` handler runs at all, so the
  new submit-time check and its visual feedback would never execute when the field was empty. The
  SKU `<select>` keeps `required` since its own empty-value option is still a real "unselected"
  state the browser can legitimately block on, and that path isn't part of this ticket.
  Discovered this via the Playwright run: `input-invalid`/`field-error` were both `false` and the
  modal stayed open only because of native validation, not the app's own logic — confirmed by
  re-running after removing `required`, at which point the flags and the message both appeared.
- **Verified via a local Vite dev server instead of `docker compose up --build frontend`.** The
  dockerized frontend build runs a whole-project `tsc -b`, which currently fails on
  `ScenarioRunDetailPage.tsx` (unused variable, a type mismatch on a `demonstrates` prop) — a file
  a concurrent agent is actively mid-edit on and explicitly out of scope for me to touch. Since
  the default `VITE_*_SERVICE_URL` env vars already point at `localhost:808x`, matching the
  already-running `docker compose` backend services exactly, a local `npm run dev` exercises the
  same real HTTP calls to the same real services without needing that unrelated file to compile.
  This is not a mock — `curl` and the Playwright run both hit the live inventory-service and
  order-service and got real `200`/`201` responses.
- **Kept the drag-detection scoped to mousedown-target tracking**, per the ticket's illustrative
  fix, rather than e.g. a timer-based "was this a click or a drag" heuristic — the mousedown/click
  target comparison is simpler and directly matches the root cause described (click target is the
  overlay, but the gesture didn't originate there).

## Deliberately not covered

- Did not touch or fix `frontend/src/pages/ScenarioRunDetailPage.tsx`, which currently fails
  `tsc -b` (`matchDemonstratesPoint` unused, and a `demonstrates` prop type mismatch around line
  367) and blocks the dockerized frontend image build. That file belongs to a concurrent agent's
  in-progress work per the delegation prompt's explicit scope boundary; flagging it here so
  whoever owns that file is aware it currently breaks the full-project build, not just its own
  page.
- Did not rebuild/redeploy the dockerized `frontend` service — left it as originally running,
  since rebuilding it was blocked by the above and out of scope to fix.
- Did not address issue #22 (SKU dropdown → inventory table) or #23 (button/input colors) — both
  explicitly deferred by the ticket to land after this fix.
- Did not add automated/CI test coverage (e.g. a Playwright test file committed to the repo) for
  either bug — the ticket asked for verification against a running system, not new test
  infrastructure, and the repo has no existing Playwright setup to extend. The manual verification
  script was deleted after use rather than left as dead/uncommitted test scaffolding.
- Did not investigate why the `kafka` container showed `unhealthy` in `docker compose ps` output
  observed near the end of this session — it was already running before I started, I did not
  touch any Kafka-related config, and order/inventory HTTP paths (which don't require Kafka to be
  healthy for a simple GET) worked fine throughout verification. Worth a look by whoever manages
  the shared stack if it persists.
