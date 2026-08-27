# Frontend Styling Contract

This is the internal naming and structure contract for CSS in this frontend. It governs how new
tokens and styles are added to `frontend/src/index.css` — it is not a palette. No component or page
should invent a color or spacing value outside the scheme described here.

This is a plain-CSS project: React + TypeScript + Vite, CSS custom properties for theming, no CSS
framework (no Tailwind, no styled-components, no CSS Modules). All rules below assume that stays
true; if the project ever adopts a CSS framework, this document needs a rewrite, not a patch.

## Why this exists

`frontend/src/index.css` already uses CSS custom properties (`--bg`, `--text`, `--accent`, `--success`,
etc.) for light/dark theming, but nothing distinguishes *what kind* of thing a token represents — a
generic theme color, a status color, and a per-service identity color all live in the same flat
namespace. Two pieces of work need a stable namespace to build against before they start:

- An interactive theme session that will assign real palette values to whatever namespace this
  contract defines.
- A scenario-run flow visualization that needs a distinct, stable color per service (order, payment,
  inventory, fulfillment, at minimum) to render service identity in the UI.

This document defines the namespace once, structurally, so both pieces of work — and everything
after them — reference the same scheme instead of each inventing its own colors that later collide.
It applies the same "agree the shape first" idea this project already uses for cross-service
contracts (`docs/openapi/`, `docs/events/`), one layer down, scoped to the frontend only.

**This document defines naming and structure only.** It does not assign final hex values, exact
spacing numbers, or a finished palette. Examples below with a concrete value (e.g. `#5b3df0`) are
illustrative placeholders carried over from the current stylesheet, not a locked-in design decision.

## Token namespace

All tokens are CSS custom properties, declared in `:root` (and overridden per-property under the
existing `@media (prefers-color-scheme: dark)` block where a value needs to differ in dark mode).
Every token name uses a `--<category>-<name>[-<variant>]` shape. Four categories:

### 1. `--color-service-*` — per-service identity

One token per backend service that appears in cross-service UI (the scenario timeline, a future flow
diagram, service badges). Name after the service, not after where it's used:

```css
--color-service-order: #____;
--color-service-payment: #____;
--color-service-inventory: #____;
--color-service-fulfillment: #____;
```

Add a new `--color-service-<name>` token if a fifth service becomes user-visible in the UI (e.g. a
notification service). Don't reuse an existing service's token for a different service, even
temporarily — a collision here is exactly what this contract exists to prevent.

If a lighter/darker or background variant of a service color is ever needed (e.g. for a badge
background analogous to today's `--success-bg`), extend the same name: `--color-service-order-bg`.

### 2. `--color-status-*` — outcome/state colors

Renames and generalizes the current flat `--success` / `--failure` / `--pending` / `--expected` set
into one category, each with a `-bg` companion for the tinted-background badge pattern already used
by `.status-success`, `.status-failure`, etc. in `frontend/src/index.css`:

```css
--color-status-success: #____;
--color-status-success-bg: #____;
--color-status-failure: #____;
--color-status-failure-bg: #____;
--color-status-pending: #____;
--color-status-pending-bg: #____;
--color-status-expected: #____;
--color-status-expected-bg: #____;
```

`expected` keeps its current meaning (see the existing comment above `.status-expected` in
`index.css`): a legitimate designed outcome or benign not-yet-reported state, not an actual fault.
Any new order/scenario outcome that needs its own color (as opposed to reusing one of these four)
gets a new `--color-status-<name>` pair, not a bare hex value inline.

### 3. `--color-*` — general theme tokens

Everything that isn't a service or a status: today's `--text`, `--text-h`, `--bg`, `--bg-alt`,
`--border`, `--accent`. These keep their existing short names rather than being forced under a
`--color-theme-*` prefix — they're already unambiguous and renaming them churns every call site for
no naming-collision benefit. New general theme tokens follow the same flat `--<purpose>` shape as the
current ones (e.g. a future `--overlay` for modal backdrops, replacing the current inline
`rgba(0, 0, 0, 0.45)` in `.modal-overlay`).

### 4. `--space-*` — spacing scale

Not present today (`frontend/src/index.css` uses ad hoc pixel values — `8px`, `12px`, `16px`, `24px` —
directly in each rule). This contract does not mandate migrating existing rules, but any *new* rule
should reach for a spacing token instead of a new bare pixel value, using a small numbered scale tied
to the pixel values already in common use:

```css
--space-1: ____;  /* current common usage: 4px */
--space-2: ____;  /* current common usage: 8px */
--space-3: ____;  /* current common usage: 12px */
--space-4: ____;  /* current common usage: 16px */
--space-6: ____;  /* current common usage: 24px */
--space-8: ____;  /* current common usage: 32px */
```

The numbering has gaps (no `-5`, `-7`) on purpose, matching the non-linear jumps already visible in
the stylesheet (4/8/12/16/24/32), so a future in-between value has a slot without renumbering
everything else. Don't introduce a spacing value outside this scale without adding it to the scale
first — a one-off `10px` inline defeats the point of having a scale.

## File organization

Tokens stay in `frontend/src/index.css`'s `:root` block — this project does not warrant a separate
tokens file or a build-time CSS pipeline. The current single-file stylesheet is small enough (under
1000 lines) that splitting it would add indirection without a real payoff. Within `:root`, group
tokens under the four categories above with a comment header per group, in this order: general theme
tokens first (they're what every other rule depends on implicitly), then status, then service, then
spacing. Dark-mode overrides stay in the existing `@media (prefers-color-scheme: dark)` block,
re-declaring only the properties that actually change under dark mode, exactly as today.

If `index.css` grows enough that scrolling past the token block becomes a real cost (a judgment call
for whoever hits it, not a line-count trigger set here), split tokens into their own
`frontend/src/tokens.css` imported at the top of `index.css`. Don't do that preemptively.

## Authoring conventions

- **Components and pages reference custom properties, never raw hex values, for anything covered by
  a category above.** `var(--color-service-order)`, not `#5b3df0` inline in a `.tsx` file or in a new
  CSS rule. This already holds in the current codebase — a grep across `frontend/src/pages` and
  `frontend/src/components` today finds zero inline hex colors — and this contract keeps it that way
  going forward.
- **Raw hex is acceptable only for one-off, non-reusable effects that don't represent a themed
  concept** — e.g. the existing `box-shadow: 0 4px 12px rgba(91, 61, 240, 0.16)` on `.scenario-card:hover`,
  which is a shadow tint derived from the accent color for one specific hover effect, not a reusable
  token. If the same one-off value is reached for a second time anywhere else, that's the signal it
  should become a token instead of staying inline.
- **New spacing values in new rules use `var(--space-N)`.** Existing bare-pixel spacing in current
  rules is not required to migrate as part of adding this contract (that migration is a separate,
  later task) — this convention binds new and edited rules going forward.
- **Component-scoped class names stay in `index.css`**, following the existing flat, page-or-feature
  prefixed naming already in use (`.scenario-card`, `.timeline-entry`, `.order-summary-card`). This
  contract does not change class-naming conventions — only the custom-property/token layer.

## What consumes this contract

- The interactive theme session assigns real values to every `#____` placeholder token named above.
- The scenario-run flow visualization on `frontend/src/pages/ScenarioRunDetailPage.tsx` consumes the
  four `--color-service-*` tokens to give each service a stable, distinct identity color.
- Any future page or component that needs a color, status indicator, or spacing value reaches for an
  existing token under one of the four categories, or extends a category following its naming
  pattern, before ever reaching for a bare literal.
