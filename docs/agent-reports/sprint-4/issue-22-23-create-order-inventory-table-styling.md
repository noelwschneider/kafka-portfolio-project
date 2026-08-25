# Issue #22 / #23 — inventory table and form styling on the New Order modal

## What changed

- `frontend/src/pages/CreateOrderPage.tsx` — replaced the SKU `<select>` + free-text quantity line
  with a real inventory table (Product, SKU, Available, Add) rendered from `listInventory()`. Each
  row's "Add" button pushes (or increments) a line in a new "order items" list below the table;
  each added line shows the product name, a quantity input, and a Remove button. Added a
  `noLinesError` submit-time check ("Add at least one item.") since there's no longer a placeholder
  empty line to fall back on. Wired `className="button-primary"` onto the "Place order" submit
  button. Kept the #24 fixes untouched — quantity `''`-vs-`0` empty-state handling carried over
  unchanged onto the new per-line quantity input, and `OrdersListPage.tsx`'s mousedown-tracking
  modal-close logic was not touched at all.
- `frontend/src/index.css` — `.order-form input`/`.order-form select` and `.line-item input`
  backgrounds changed from `var(--bg)` (blended into the modal) to `var(--bg-alt)` (visible
  against both modal and page background). Added `.inventory-table` / `.line-item-name` layout
  rules. Gave `.button-secondary` an explicit `color`/`border-color` and a `:hover` rule (it
  previously had only `background: transparent`, with no readable secondary treatment of its own).

## Judgment calls

- **No price column.** Checked `frontend/src/api/inventory.ts`'s `InventoryItem` type and
  `docs/openapi/inventory-service.yaml` — no price field exists on inventory items.
  `docs/openapi/order-service.yaml` (`OrderItem.unitPrice`, line ~337) states unit price is
  "Captured at order creation from the Order Service's seeded SKU price map... Not fetched from
  Inventory Service." There is no real price value available anywhere in the create-order flow, so
  I left the price column out rather than fabricate a number, and left an explanatory comment in
  the JSX pointing at both contract files. This is a contract-shaped gap (Inventory Service doesn't
  carry price, Order Service doesn't expose its price map read-only) rather than a frontend bug — not
  proposing a contract change since inventing a price-lookup endpoint is out of scope for a frontend
  polish ticket.
- **Interaction model for issue #22**: chose a table with a per-row "Add" button (adds with
  quantity 1, or increments the existing line's quantity by 1 if already added) plus a separate
  "order items" list below showing quantity inputs, over augmenting the old dropdown-then-quantity
  flow in place. The delegation prompt explicitly allowed either shape; this one lets the table stay
  purely a scannable reference (no inline quantity editing cluttering table rows) while quantity
  editing/removal stays with the line list, which is closer to a real cart pattern.
- Removed the old "Add item" button and the always-present empty line entirely, since there's no
  more dropdown for it to leave blank — items now only enter the order list via the table's Add
  button. Disabled the per-row Add button when `availableQuantity - reservedQuantity <= 0` so you
  can't add a line for stock that isn't there (the old dropdown didn't filter this either, so this
  is a small strictly-additive improvement, not scope creep — it falls directly out of building the
  table view).
- Button-secondary hover state: overrode the global `button:hover { border-color: var(--accent) }`
  with `border-color: var(--border)` specifically on hover for `.button-secondary`, so Cancel stays
  visually calm/secondary even on hover instead of picking up an accent-purple outline that would
  compete with the primary button.

## How this was verified

TypeScript, lint, and production build all clean:

```
$ cd frontend && npx tsc --noEmit
(no output)

$ npm run lint
> frontend@0.0.0 lint
> oxlint
(no errors)

$ npm run build
...
✓ built in 522ms
```

Stack was already running (`docker compose ps` showed all services `running` before I touched
anything — left as found, not started or stopped by me except rebuilding the `frontend` image in
place):

```
$ docker compose up -d --build frontend
...
 Container orderfulfillment-frontend  Recreate/Running
```

Full end-to-end browser verification via Playwright (chromium, already present in
`frontend/node_modules`) against the live `docker compose` stack at `http://localhost:5173`:

```
INVENTORY TABLE HEADERS: ["Product","SKU","Available",""]
INVENTORY TABLE ROWS: [["USB-C Dock","SKU-002","5","Add"],["Developer Mug","SKU-003","100","Add"],["External SSD","SKU-004","2","Add"],["Mechanical Keyboard","SKU-001","6","Add"]]
PRIMARY BUTTON BG: rgb(91, 61, 240)
SECONDARY BUTTON BG: rgba(0, 0, 0, 0)
CUSTOMER INPUT BG: rgb(247, 247, 249)
LINE ITEM AFTER ADD: USB-C DockRemove
FORM ERROR TEXT (if any): null
MODAL STILL OPEN: false
CONSOLE LOGS:
```

`rgb(91, 61, 240)` is `--accent` (the primary button is filled purple); `rgba(0,0,0,0)` (transparent)
plus the new border/color rule is the secondary treatment; `rgb(247, 247, 249)` is `--bg-alt`, so the
customer-id input now has a visible background distinct from both `--bg` (white/`#17171b`) and the
modal panel. No console errors during the whole flow.

Confirmed the order landed for real via the Order Service API directly (not just "modal closed"):

```
$ curl -s "http://localhost:8081/api/orders?size=5" | python3 -m json.tool
{
    "content": [
        {
            "id": "order-20089",
            "customerId": "verify-customer-22-23",
            "status": "FULFILLED",
            "totalAmount": 189.0,
            "createdAt": "2026-08-25T19:31:44.114042Z",
            "updatedAt": "2026-08-25T19:31:45.053242Z"
        },
        ...
```

`order-20089` is the order placed by the Playwright script (customer id `verify-customer-22-23`,
one line: USB-C Dock, quantity 1 from the Add button), and it reached `FULFILLED` — a real order,
not a UI-only success state.

## Deliberately not covered

- Did not add a way to adjust quantity by clicking "Add" multiple times vs. editing the number
  input — both work (Add increments by 1, the input accepts any 1–100 value), but there's no
  dedicated stepper UI; considered out of scope for what the two tickets asked for.
- Did not address the price gap at the contract level (see Judgment calls) — flagging it here as a
  known limitation rather than opening a contract change, since neither ticket asked for pricing to
  become available pre-order, only for the table to show price "if available."
- Did not visually screenshot/eyeball the rendered page (no image capture tool available in this
  session) — verification is via Playwright's DOM/computed-style assertions and a real API round
  trip, not a human-reviewed screenshot. If pixel-level review is wanted, that's a gap.
- Left `dist/` untracked and removed the local build output after `npm run build`; did not touch
  `frontend/Dockerfile` or any other build config.
