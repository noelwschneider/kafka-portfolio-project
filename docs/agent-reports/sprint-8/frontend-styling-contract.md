# Frontend styling contract (#54)

## What changed

- `frontend/STYLE_GUIDE.md` (new file) — the frontend styling contract: a CSS custom-property
  naming scheme with four categories (`--color-service-*`, `--color-status-*`, general `--color-*`
  theme tokens, `--space-*` spacing scale), file-organization guidance (tokens stay in
  `frontend/src/index.css`'s `:root`, no split file yet), and authoring conventions (components
  reference custom properties, raw hex only for genuine one-offs, new spacing reaches for the
  scale). No hex values, no final spacing numbers — placeholders (`#____`, `____`) throughout, per
  the issue's explicit deferral of palette decisions to #56.

No other files were touched. This is a docs-only deliverable per the issue's scope; the refactor
onto this contract is #55, not this task.

## How this was verified

Grounded the doc in the actual current stylesheet and components rather than an idealized design:

```
$ wc -l frontend/src/index.css
801 frontend/src/index.css
```

Read the full file (all 801 lines) to confirm the existing token set (`--text`, `--bg`, `--border`,
`--accent`, `--success`/`-bg`, `--failure`/`-bg`, `--pending`/`-bg`, `--expected`/`-bg`), the
`:root` + `@media (prefers-color-scheme: dark)` structure, and the ad hoc pixel spacing (4/8/12/16/24/32px)
used throughout — these are what the new categories in the doc map onto and cite as current usage.

Verified the "components never use raw hex" convention actually holds today before writing it into
the doc as a rule:

```
$ grep -rn "#[0-9a-fA-F]\{3,6\}" frontend/src/pages frontend/src/components 2>/dev/null | grep -v "\.css"
frontend/src/pages/ScenarioRunDetailPage.tsx:321:  // Display-only: strip the `run-` prefix so the heading reads "Scenario run #227" instead of
frontend/src/components/StatusBadge.tsx:37:      {isExpectedOutcome && <span aria-hidden="true">&#9432; </span>}
```

Both hits are non-color (a `#` in a comment about display text, and an HTML entity), confirming zero
inline hex colors in `.tsx` files today — the "raw hex only for genuine one-offs" rule is descriptive
of the current codebase, not aspirational.

Read `frontend/src/pages/ScenarioRunDetailPage.tsx` (the consumer named in the issue for #57) to
confirm it currently has no color logic of its own to collide with the new `--color-service-*`
tokens, and cross-checked `docs/planning/sprint-8/sprint-8-plan.md` to confirm the `--color-service-*`
naming matches what #56/#57 are already planned to consume.

CI verification on the actual PR:

```
$ gh pr checks 59 --watch
changes            pass   6s
Required checks    pass   2s
frontend           pass   17s
fulfillment-service   skipping
inventory-service     skipping
order-service         skipping
payment-service       skipping
scenario-service      skipping
```

Path-filtered CI correctly ran only the `changes` and `frontend` jobs (a `.md` file under
`frontend/`) and skipped every backend service build, and `Required checks` passed.

## Judgment calls

- **Location: `frontend/STYLE_GUIDE.md` over `docs/frontend/styling-conventions.md`.** Chose the
  former because it sits next to the code it governs (same convention as e.g. a service-local
  `README.md`), and because the issue explicitly said not to place it under `docs/openapi/`-style
  cross-service contract directories — this is frontend-internal only, so keeping it inside
  `frontend/` rather than under top-level `docs/` reinforces that distinction rather than implying
  it's a cross-service contract by its very location.
- **Kept existing short names (`--text`, `--bg`, `--accent`, etc.) instead of renaming them under a
  new `--color-theme-*` prefix.** Renaming would force every current call site to change as part of
  a docs-only task, which is explicitly out of scope (that's #55's job), and the existing names
  aren't actually ambiguous today. Documented them as already-conformant to the general `--color-*`
  category rather than retrofitting a longer prefix.
- **Introduced a `--space-*` spacing scale even though the current stylesheet has none.** The issue
  asked me to decide "spacing-scale conventions if you introduce a scale," implying it's optional.
  I introduced one because `frontend/src/index.css` already exhibits a consistent informal scale
  (4/8/12/16/24/32px) — naming it costs nothing and gives #55/#56/#57 a place to put new spacing
  without reinventing pixel values ad hoc. Did not mandate migrating existing bare-pixel rules onto
  it, since that's a mechanical refactor task, not a naming decision.
- **Did not stage or commit the two unrelated files already modified in the working tree**
  (`frontend/src/api/orders.ts`, `frontend/src/pages/OrdersListPage.tsx`) — these were pre-existing
  uncommitted changes present before this task started, unrelated to the styling contract, and
  outside this issue's scope. Left them untouched in the working tree exactly as found.

## Deliberately not covered

- No final color or spacing values — explicitly deferred to #56 (interactive theme session) per the
  issue.
- No code refactor onto the new token namespace — that's #55, not this task.
- No visual verification (nothing renders differently; this is a docs-only change, so there was no
  UI to look at).
- Did not touch the two unrelated pre-existing uncommitted changes found in the working tree at
  session start (`frontend/src/api/orders.ts`, `frontend/src/pages/OrdersListPage.tsx`) — out of
  scope for this task and not mine to resolve.

## Reproducing this from a clean clone

PR: https://github.com/noelwschneider/kafka-portfolio-project/pull/59 (branch
`docs/frontend-styling-contract`, references "Closes #54"). CI green (`Required checks` passed). Not
merged — merging is the developer's call per project rules.
