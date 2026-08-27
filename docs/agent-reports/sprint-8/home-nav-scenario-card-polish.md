# Home nav rename, visited-tab color bug, scenario-card redesign, subheader copy, redundant back button

## What changed

- `frontend/src/App.tsx` — renamed the first `NAV_ITEMS` entry's label from `'Overview'` to `'Home'` (route path `/` unchanged). Removed `onBack` from `ScenarioRunDetailRoute` along with its now-unused `useNavigate()` call, since `ScenarioRunDetailPage` no longer takes an `onBack` prop.
- `frontend/src/index.css` — added `.app-nav-link:visited { color: var(--text); }` and `.app-nav-link.active:visited { color: #fff; }` after the existing `.app-nav-link.active` rule, to out-specificity the global `a:visited { color: var(--accent) }` rule for nav tabs specifically. Redesigned `.scenario-card`: swapped `border-top: 3px solid var(--accent)` for `border-left: 3px solid var(--accent)` (avoids the rounded-corner collision with `border-radius: 8px`), and changed `background: var(--bg-alt)` to `background: color-mix(in srgb, var(--accent) 5%, var(--bg-alt))` for a more clearly distinct card surface in both light and dark mode.
- `frontend/src/pages/OverviewPage.tsx` — rewrote the Scenarios subheader from "See how the system handles a variety of scenarios." to a sentence stating the catalog/orders are staged but every scenario run triggers real HTTP requests, Kafka events, and processing/persistence — not a frontend animation.
- `frontend/src/pages/ScenarioRunDetailPage.tsx` — removed the `<button onClick={onBack}>Back to scenarios</button>` from the page header, and removed `onBack` from `Props` and the component's destructured parameters (it was that button's only use in the file).

## How this was verified

Static checks from `frontend/`, both clean on the final branch state:

```
$ npx tsc -b --noEmit && echo "TSC_OK" && npx oxlint && echo "LINT_OK"
TSC_OK

Found 0 warnings and 0 errors.
Finished in 5ms on 40 files using 12 threads.
LINT_OK
```

Live verification against the real dev stack (backend services already running from a prior
session; I only started/stopped a `vite` dev server on port 4321, which I confirmed was killed
afterward):

1. Playwright/Chromium (already cached locally — no new install needed beyond confirming the
   cache) navigating `http://localhost:4321/`, clicking Architecture, then clicking back to Home
   (real SPA navigation, which is what registers `:visited` state in a real browser):
   ```
   Architecture tab (inactive, visited) color: rgb(58, 58, 63)
   Orders tab (inactive) color: rgb(58, 58, 63)
   ```
   Both equal `--text` (`#3a3a3f` = `rgb(58, 58, 63)`), confirming the visited nav tab no longer
   renders in accent green. A full-page screenshot at this point also shows the nav labeled "Home"
   (not "Overview"), gray Architecture/Orders tabs, the redesigned scenario cards (left accent
   stripe, tinted background clearly distinct from the page background), and the new subheader
   copy.
2. Clicked "Run Scenario" on the Standard Fulfillment card and let it navigate to the scenario-run
   detail page. This triggered a real run (correlation ID `9886c2d7-ba0b-4d80-9900-244b9dba7f0f`,
   status `RUNNING`, live SSE connection attempt visible), confirming the page still functions with
   `onBack` removed. Checked for the button directly:
   ```
   Back to scenarios button present: false
   ```
   Confirming the redundant button is gone and the page didn't break without it.

Both throwaway Playwright scripts and their screenshots were deleted from the scratchpad after use;
nothing was left behind in the repo.

## Judgment calls

- **Scenario-card background**: used `color-mix(in srgb, var(--accent) 5%, var(--bg-alt))` rather
  than a flat new color, so the tint automatically follows `--accent` and `--bg-alt` in both light
  and dark mode without a second set of dark-mode overrides. 5% was chosen to be clearly visible
  next to the page background (confirmed via screenshot) without competing with the left accent
  stripe or the status badges inside the card.
- **Accent stripe placement**: moved to `border-left` per the delegation's own suggested fix rather
  than the "round only the bottom corners" alternative — simpler, and it's the option the prompt
  called out as reading cleanly with `border-radius`.
- **Subheader copy**: didn't reuse the developer's rough wording verbatim ("fake store, real
  services, real feedback") since "real feedback" was vague about *what* is real. Used "real HTTP
  requests, real Kafka events, and real processing and persistence" instead, naming the specific
  things this project's central premise (per CLAUDE.md's opening paragraph) actually claims to be
  real, since that specificity is the whole point of the distinction being drawn.
- **`OrderDetailPage.tsx`'s back button**: left untouched. Its `onBack` prop is still wired from
  `App.tsx`'s `OrderDetailRoute` and used nowhere else questionable — it's live code serving a real
  affordance ("Back to orders" → `/orders`), not dead code from an unrelated cause. The prompt was
  explicit that only a genuinely-confirmed-dead-code situation would justify touching it, and
  redundancy-with-a-nav-tab alone (which does technically also apply here, since there's an Orders
  nav tab) isn't the same as dead code — the prompt drew that line deliberately to keep this
  decision out of my hands, so I didn't extend the scenario-run reasoning to it.

## Deliberately not covered

- Did not visually re-check `OrderDetailPage.tsx` or any other page beyond Home and the
  scenario-run detail page — out of scope, since nothing in this delegation touched them (aside
  from the explicit check-and-decline on its back button, above).
- Did not check the visited-tab fix in a real, persistent-profile browser across a full
  quit/relaunch cycle — Playwright's `:visited` behavior from real in-session navigation is a
  faithful proxy for the browser's `:visited` mechanism, but a full manual click-through in an
  actual long-lived browser profile is what the prompt suggested as the more reliable repro path
  if available; I used the Playwright route instead since it's an actual browser (not simulated
  animation) and it registered `:visited` correctly, matching what the prompt anticipated might not
  reliably happen in "automated browser tooling."
- Did not perform a dedicated dark-mode screenshot pass; the `color-mix` background fix for
  `.scenario-card` and the `:visited` fix are both written in terms of the same CSS custom
  properties that already vary by `prefers-color-scheme`, so they should carry over, but this
  wasn't independently screenshotted in dark mode. A live human design review was already expected
  regardless per the delegation prompt.
