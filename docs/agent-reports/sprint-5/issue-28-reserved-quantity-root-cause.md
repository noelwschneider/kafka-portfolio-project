# Issue #28 — `reserved_quantity` not zeroing after `FULFILLED`: root cause

Investigation only, per the delegation scope. No reservation/compensation code was modified.

## What changed

Nothing in source. This is a read-only diagnosis against the live `docker compose` stack (already
running before this session — not started, restarted, or torn down by this work). No files under
`services/` were created or modified.

## Root cause

**Inventory Service has no consumer for any fulfillment-side event. `reserved_quantity` is not
"stuck" or buggy — it is only ever decremented by the `PaymentRejected` compensation path, and
nothing analogous exists for the success path, by design.**

Inventory Service registers exactly two `@KafkaListener` beans, confirmed by grep across the whole
service (`services/inventory-service/src/main/java/com/orderfulfillment/inventory/`):

- `InventoryOrderEventsConsumer` — `orders.events`, acts only on `OrderCreated` (line 66: `if
  (!EventTypes.ORDER_CREATED.equals(envelope.eventType())) { return; }`); `PaymentRequested` on the
  same topic is explicitly skipped.
- `InventoryPaymentEventsConsumer` — `payments.events`, acts only on `PaymentRejected` (line 58, same
  pattern); `PaymentAuthorized` is explicitly skipped.

Neither listens to `fulfillment.events` (where `ShipmentCreated` is published). There is no third
consumer. This matches the frozen contract, not just an implementation gap: `docs/events/event-catalog.md`
and `docs/events/schemas/ShipmentCreated.schema.json` both document `ShipmentCreated` as "consumed by
Order Service" only, and `docs/order-state-machine.md` lists `InventoryReleased` as "inventory-side
compensation only," with no corresponding "inventory-side consumption" entry for the `FULFILLED`
transition. The contracts and the code agree with each other — there's nothing to reconcile there.

`InventoryReservationExecutor.release()` (the only method that ever decrements `reservedQuantity`)
is wired to fire solely from `InventoryPaymentEventsConsumer` on `PaymentRejected`. `ReservationStatus`
has exactly three values — `RESERVED`, `RELEASED`, `FAILED` — with no `CONSUMED`/`FULFILLED` state,
meaning the schema itself was never extended to represent "this reservation was consumed by a
successful order," only "this reservation was returned to stock because the order failed."

This is a known, previously-documented, deliberate deferral, not new information:

- `InventoryService.restoreForDemo`'s Javadoc (`services/inventory-service/.../InventoryService.java:82-93`)
  states outright: "reservations are only released on the payment-failure compensation path (never on
  successful fulfillment — see docs/agent-reports/sprint-2/deployment-execution-report.md §6)."
- `docs/agent-reports/sprint-2/deployment-execution-report.md` §6 ("DEFECT — reset cannot restore
  stock, and the auto-reset makes it worse") diagnosed this exact mechanism in Sprint 2, and explicitly
  proposed and deferred **Option B — release reservations when an order reaches a terminal success
  state** as "the domain-correct fix... but it changes saga behaviour, touches the compensation path
  shared with `payment-failure`, and deserves its own design pass," in favor of shipping **Option A** —
  giving the demo-reset path (`restoreForDemo`) the ability to zero `reservedQuantity` directly. Option
  A is what's live today; Option B was never picked up.

So the mechanism behind issue #28's symptom is exactly the same one Sprint 2 already named and chose
not to fix generally, just observed again independently during Sprint 4's scenario pass without the
connection being made at the time.

## How this was verified

Stack was already up (`docker compose ps`, all 10 services healthy, 3h uptime) — used as-is, not
restarted.

### 1. Direct reproduction: a fresh order through `FULFILLED`, watched end to end

Baseline — SKU-002 had zero reservations before this run:
```
$ curl -s http://localhost:8082/api/inventory/SKU-002
{"sku":"SKU-002","displayName":"USB-C Dock","availableQuantity":5,"reservedQuantity":0,"version":8, ...}
```
Placed a real order (not a scenario-service simulation) directly against Order Service:
```
$ curl -s -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
    -d '{"customerId":"cust-repro-28","items":[{"sku":"SKU-002","quantity":1}]}'
{"id":"order-20160","status":"PENDING","createdAt":"2026-08-25T22:23:49.918973626Z"}
$ # polled GET /api/orders/order-20160
poll 1: FULFILLED
```
Inventory and the reservation ledger immediately after `FULFILLED`:
```
$ curl -s http://localhost:8082/api/inventory/SKU-002
{"sku":"SKU-002","displayName":"USB-C Dock","availableQuantity":5,"reservedQuantity":1,"version":9, ...}

$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c \
    "select id, order_id, sku, quantity, status from inventory_service.inventory_reservations where order_id='order-20160';"
        id         |  order_id   |   sku   | quantity |  status
-------------------+-------------+---------+----------+----------
 resv-4254-SKU-002 | order-20160 | SKU-002 |        1 | RESERVED
```
`reservedQuantity` went `0 -> 1` on reservation and **stayed at 1** after the order reached
`FULFILLED`; the reservation row is still `RESERVED`, never transitioned. Reproduced directly, not
inferred.

### 2. Positive control: the same mechanism (`InventoryReservationExecutor`), the release path that
   *does* exist, on `PAYMENT_FAILED`

Baseline SKU-001: `availableQuantity:10, reservedQuantity:3`. Ran the `payment-failure` demo scenario
(real order, real Kafka events, real Postgres writes — not a frontend simulation):
```
$ curl -s -X POST http://localhost:8085/demo/scenarios/payment-failure
{"id":"run-245", ...}
$ # polled to COMPLETED — orderId order-20161, timeline: OrderCreated -> InventoryReserved ->
$ # INVENTORY_RESERVED -> PAYMENT_PENDING -> PaymentRequested -> PaymentRejected -> PAYMENT_FAILED
```
After completion:
```
$ curl -s http://localhost:8082/api/inventory/SKU-001
{"sku":"SKU-001","displayName":"Mechanical Keyboard","availableQuantity":10,"reservedQuantity":3,"version":31, ...}

$ docker exec orderfulfillment-postgres psql ... -c \
    "select id, order_id, sku, quantity, status from inventory_service.inventory_reservations where order_id='order-20161';"
        id         |  order_id   |   sku   | quantity |  status
-------------------+-------------+---------+----------+----------
 resv-4255-SKU-001 | order-20161 | SKU-001 |        1 | RELEASED
```
`reservedQuantity` for SKU-001 is `3` both before and after (version incremented 29->31: one write to
reserve, one to release), and the reservation row transitioned to `RELEASED`. This isolates the
defect precisely: `InventoryReservationExecutor.release()` itself is correct and does exactly what
it's supposed to when it's invoked — the only thing missing is anything that invokes the equivalent
operation on the success path. There is no bug hiding in the release logic to find.

### 3. Invariant check — `reserved_quantity <= available_quantity`

Full inventory state after both repro runs above:
```
$ curl -s http://localhost:8082/api/inventory
SKU-003: available=100 reserved=60
SKU-001: available=10  reserved=3
SKU-002: available=5   reserved=1
SKU-004: available=2   reserved=2
```
Holds for every SKU, including the tightest case (`SKU-004`, `reserved == available`, `freeQuantity
0`). Confirmed the CHECK constraints are still in place at the schema level, not just enforced in
application code:
```
$ docker exec orderfulfillment-postgres psql -U orderfulfillment -d orderfulfillment -c "\d inventory_service.inventory_items"
Check constraints:
    "inventory_items_available_quantity_check" CHECK (available_quantity >= 0)
    "inventory_items_reserved_quantity_check" CHECK (reserved_quantity >= 0)
    "inventory_items_reserved_within_available" CHECK (reserved_quantity <= available_quantity)
```
**No new or more severe invariant violation was found.** The original observation from Sprint 4 (the
invariant held in every case checked) still holds under a fresh, directly-observed reproduction. This
is the expected result given the mechanism: `available_quantity` is never decremented on the happy
path either (nothing "consumes" real stock down), so `reserved_quantity` climbing without bound erodes
`freeQuantity` toward zero (this is exactly Sprint 2's §6 defect — a demo-usability problem already
mitigated by Option A) but cannot make `reserved_quantity` exceed `available_quantity` by itself.

### 4. Confirmed the contract, not just the code, omits Inventory Service as a `ShipmentCreated` consumer

```
$ grep -rn "ShipmentCreated" docs/events/
docs/events/event-catalog.md:75:| `fulfillment.events` | Fulfillment Service | `ShipmentCreated` |
docs/events/event-catalog.md:382: ...
docs/events/schemas/ShipmentCreated.schema.json:5: "...Published by Fulfillment Service on
    fulfillment.events, consumed by Order Service. ..."
```
`ShipmentCreated` is documented as consumed by Order Service only. Inventory Service was never meant
to see it under the current (v1, no separate dispatch step) design — this is not a missing wire-up of
an otherwise-intended subscription; the intended subscription doesn't exist in the contract either.

### 5. Confirmed no third `@KafkaListener` exists anywhere in Inventory Service

```
$ grep -rn "@KafkaListener" services/inventory-service/src/main/java/
InventoryPaymentEventsConsumer.java:49
InventoryOrderEventsConsumer.java:57
```
Exactly two, matching `orders.events`/`OrderCreated` and `payments.events`/`PaymentRejected`. Ruled
out "there's a consumer that's supposed to fire but is broken" — there is no such consumer to be
broken.

## Judgment calls

- I used the shared, already-running stack rather than a fresh one, and created real orders
  (`order-20160`, `order-20161`) and a real scenario run (`run-245`, `payment-failure`) against it,
  the same way `docs/agent-reports/sprint-4/issue-25-...md` did for the same reason: tearing down and
  rebuilding would reset Kafka (a separate, already-diagnosed defect in that same report) and add
  noise unrelated to this investigation, and this task didn't call for a clean-room environment to
  answer the question asked. I picked SKU-002 for the primary repro specifically because its
  `reservedQuantity` was `0` going in, so `0 -> 1 -> stays 1` is unambiguous — SKU-003/004 (the
  originally-observed SKUs) already had nonzero `reservedQuantity` baked in from prior runs, which
  would have made "did it zero out" less directly legible.
- I treated the Sprint 2 report and the `restoreForDemo` Javadoc as authoritative prior art rather
  than re-deriving the design intent from scratch, since both are explicit, dated, and directly on
  point (they name this exact mechanism and this exact tradeoff). I still ran an independent
  reproduction rather than taking their word for it, per the reproduce-before-you-explain instruction
  — sections 1–2 above are new evidence from this session, not a restatement of Sprint 2's.
- I did not attempt to reproduce the original SKU-003/SKU-004 numbers exactly (that would require
  replaying a specific `high-volume`/`inventory-contention` history) since the mechanism is
  SKU-independent and a fresh, cleaner repro on SKU-002 demonstrates the same defect with less
  ambiguity.
- I ran the `payment-failure` scenario via Scenario Service's demo API (real HTTP calls, real events,
  real Postgres writes, per Agent Rule on scenario behavior) rather than hand-crafting a
  `PaymentRejected` event, since that's the system's own supported way of forcing that path and I
  wanted the release path exercised exactly as production/demo traffic would exercise it.

## Recommendation

**This does not need a narrower fix — if the product wants `reserved_quantity` to zero out on
`FULFILLED`, Option B (the backlog item) is in fact the correct and only real fix; nothing smaller
explains or resolves this specific symptom.** Reasoning:

- The symptom is not caused by a defect in existing logic. The one component that decrements
  `reserved_quantity` (`InventoryReservationExecutor.release()`) works correctly, proven by direct
  positive control (section 2). There is nothing to patch there.
- The gap is structural, not a wiring bug: Inventory Service has no consumer for any fulfillment-side
  event, no schema state (`ReservationStatus`) representing "consumed by success," and the frozen
  event contract itself doesn't route `ShipmentCreated` to Inventory Service. A narrow fix (e.g. "just
  add a listener that calls `release()` on `ShipmentCreated`") would be underspecified as-is:
  `release()`'s current semantics are "return stock to the free pool because the order failed," and
  its `InventoryReleased` event's `reason` field is hardcoded to `"PAYMENT_REJECTED"`
  (`InventoryReservationExecutor.java:169`) — reusing it verbatim for the success path would either
  require a new reason/event shape or silently mislabel every successful fulfillment as a rejection
  in the event stream. That's exactly the "changes saga behaviour, touches the compensation path
  shared with payment-failure, deserves its own design pass" characterization Sprint 2 already gave
  it — it was accurate then and still is.
- Separately, there is no urgent operational need to do this now: Option A (already shipped) makes
  `POST /demo/reset` and the idle auto-reset correctly zero `reservedQuantity`, which is what actually
  keeps the demo usable indefinitely. Option B is a domain-modeling/saga-correctness improvement (makes
  the stock accounting honest between resets, matters for portfolio narrative accuracy about what the
  system's guarantees really are), not a bug fix blocking anything today. I'd frame the backlog item
  as "worth doing for correctness/portfolio-narrative reasons," not as "the system is broken until this
  ships."

## Deliberately not covered

- **No fix implemented or proposed as a diff** — investigation-only per explicit instruction. Option B
  itself (event/reason-field design, `ReservationStatus` schema change, whether `available_quantity`
  should also decrement on consumption) is intentionally left as an open design question for whoever
  picks up that backlog item, not decided here.
- **Did not check whether Order Service, Payment Service, or Fulfillment Service have any analogous
  "reserved but never released on success" pattern of their own.** This investigation was scoped to
  Inventory Service per the delegation; I don't have evidence either way for the other three services.
- **Did not replay or reconstruct the exact SKU-003/SKU-004 history from the original Sprint 4
  observation** (see Judgment calls) — the fresh SKU-002 repro is a cleaner, sufficient demonstration
  of the same mechanism, but if someone specifically needs those two SKUs' numbers explained
  arithmetically (e.g. for a support narrative), that arithmetic wasn't reconstructed here.
- **Did not investigate `docs/agent-reports/sprint-4/issue-25-...md`'s separate, still-open finding**
  about whether real domain consumers' idempotency ledgers (as opposed to Scenario Service's
  projection) could be compromised by a Kafka broker reset — unrelated to this issue, left as that
  report already left it.
- Left the shared `docker compose` stack exactly as found — running, healthy, not torn down. This
  session added `order-20160`, `order-20161`, and scenario `run-245` to the stack's history (net
  inventory effect: SKU-002 reservedQuantity 0->1, permanently, which is itself an instance of the very
  defect being investigated; SKU-001 net effect is zero since payment-failure's release fully reverted
  its own reservation). `payment-failure`'s own scenario logic already disables/re-enables the global
  payment-behavior override around the run (`PUT`/`DELETE /demo/payment-behavior` in the timeline), so
  no manual cleanup of that was needed.
