# 2.4 — The four domains

[← The HTTP layer](3-the-http-layer.md) · [Next: Testing →](5-testing.md)

The actual business logic: what each of the four domains does, and the temporary wiring that makes
them into a workflow before Kafka exists.

---

## Order Service — accepting an order

```java
public OrderAccepted createOrder(CreateOrderRequest request) {
    validateNoDuplicateSkus(request.items());
    List<OrderItemEntity> priced = priceItems(request.items());

    BigDecimal totalAmount = BigDecimal.ZERO;
    for (OrderItemEntity item : priced) {
        totalAmount = totalAmount.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
    }

    String orderId = idGenerator.nextOrderId();
    List<OrderItemEntity> associated = priced.stream()
            .map(i -> new OrderItemEntity(orderId, i.getSku(), i.getQuantity(), i.getUnitPrice()))
            .toList();

    OrderEntity order = persistence.createPendingOrder(orderId, request.customerId(), associated, totalAmount);

    return new OrderAccepted(orderId, order.getStatus().name(), order.getCreatedAt());
}
```

**Pricing happens server-side.** The client sends SKUs and quantities; it never sends a price. That is
the single most important line of defence in any commerce-shaped API, and it is why `CreateOrderItem`
has exactly two fields.

Prices come from `SkuPriceCatalog`, a hard-coded map of four entries:

```java
private static final Map<String, BigDecimal> PRICES = Map.of(
        "SKU-001", new BigDecimal("129.00"),
        "SKU-002", new BigDecimal("189.00"),
        "SKU-003", new BigDecimal("14.50"),
        "SKU-004", new BigDecimal("249.00"));
```

Its Javadoc records the boundary cost honestly:

> Order Service's static seeded SKU → price map (`docs/db-ownership.md`, "Where prices come from").
> Inventory Service holds stock/display_name only; no price column exists there. This is the
> project's only product catalog.

**Product data is split across two services** — `display_name` in Inventory, `unit_price` in Order —
because there is no Product Service, and the project's scope rules one out. In a real system this is
where you would say "we need a catalog service." Here it is a documented consequence of a deliberate
scope decision, which is a much better answer than pretending it isn't a seam.

Note also `new BigDecimal("129.00")` — constructed from a **string**. `new BigDecimal(129.00)` would
faithfully preserve the floating-point representation error you chose `BigDecimal` to avoid.

Two other things about this method. **The total is computed, never accepted.** And the whole thing is
a single call into `OrderPersistence.createPendingOrder`, which does the order row, the item rows, and
the first status-history row in one transaction — a boundary that matters more once
[Chapter 6](../06-outbox/README.md) adds a fourth write to it.

---

## Inventory Service — reserving stock

The most interesting logic in the project, because it is the only place where two callers genuinely
compete.

### The algorithm

`InventoryReservationExecutor.attemptReserve` is **all-or-nothing across every line of an order**:
either every line is reserved, or nothing is written and the order is rejected.

```java
// 1. Sum quantities per SKU, before checking anything
Map<String, Integer> requested = new LinkedHashMap<>();
for (OrderLine line : lines) {
    requested.merge(line.sku(), line.quantity(), Integer::sum);
}

// 2. Check every SKU against free stock, collecting shortages
List<Shortage> shortages = new ArrayList<>();
boolean anyUnknownSku = false;
Map<String, InventoryItemEntity> resolved = new LinkedHashMap<>();

for (Map.Entry<String, Integer> entry : requested.entrySet()) {
    InventoryItemEntity item = itemRepository.findById(entry.getKey()).orElse(null);
    if (item == null) {
        shortages.add(new Shortage(entry.getKey(), entry.getValue(), 0));
        anyUnknownSku = true;
        continue;
    }
    resolved.put(entry.getKey(), item);
    if (item.freeQuantity() < entry.getValue()) {
        shortages.add(new Shortage(entry.getKey(), entry.getValue(), item.freeQuantity()));
    }
}

// 3. Any shortage at all → reserve nothing
if (!shortages.isEmpty()) {
    String reason = anyUnknownSku ? "UNKNOWN_SKU" : "INSUFFICIENT_STOCK";
    return ReservationResult.failed(reason, shortages);
}

// 4. Otherwise apply every line
String reservationId = idGenerator.nextReservationId();
Instant now = Instant.now();
for (Map.Entry<String, Integer> entry : requested.entrySet()) {
    InventoryItemEntity item = resolved.get(entry.getKey());
    item.setReservedQuantity(item.getReservedQuantity() + entry.getValue());
    item.setUpdatedAt(now);
    reservationRepository.save(new InventoryReservationEntity(
            reservationId + "-" + entry.getKey(), orderId, entry.getKey(),
            entry.getValue(), ReservationStatus.RESERVED, now));
}
return ReservationResult.reserved(reservationId);
```

Four details are load-bearing.

**Check every line before writing any.** Steps 2 and 3 are fully separated from step 4. Reserving
line by line and stopping on the first shortage would leave partial reservations behind that nothing
in the system ever releases — quietly leaking stock on every out-of-stock order.

**Collect every shortage, not just the first.** The failure carries a list of
`(sku, requested, available)`, which is what lets the UI say exactly what was short by how much
rather than "unavailable."

**Reserving increments `reserved_quantity`; it does not decrement `available_quantity`.** Free stock
is the difference. This is what makes releasing distinguishable from restocking
([section 2](2-persistence.md)).

**Sum per SKU first — step 1.** This one is a bug fix, and the comment explains it precisely:

> An order carrying the same SKU on two lines used to be checked line-by-line against the
> *unmutated* free quantity, so 2 + 2 against a stock of 2 passed both checks and then applied both
> increments — reserving 4 of 2. It also collapsed to a single reservation row [...] so the release
> path would have handed back only half of what was taken, leaking stock permanently.

Two independent failures from one omission: an oversell, *and* a permanent stock leak on the
compensation path. Summing first makes the check and the write agree, and matches what
`UNIQUE (order_id, sku)` in the schema already assumed.

> **We got this wrong.** Build the summing from the start. [Chapter 10](../10-retrospective/README.md) has
> the story. Note that Order Service's own `validateNoDuplicateSkus` makes this unreachable *through
> the API* — but the executor has other callers, and a domain method should not depend on a
> validation in a different service for its correctness.

### The compensation path

`release(orderId)` is the opposite operation, and the only thing that ever gives stock back:

```java
List<InventoryReservationEntity> reservations =
        reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);
if (reservations.isEmpty()) {
    return ReleaseResult.NONE;
}
for (InventoryReservationEntity reservation : reservations) {
    InventoryItemEntity item = itemRepository.findById(reservation.getSku()).orElseThrow();
    item.setReservedQuantity(item.getReservedQuantity() - reservation.getQuantity());
    item.setUpdatedAt(now);
    reservation.setStatus(ReservationStatus.RELEASED);
    reservation.setUpdatedAt(now);
}
```

Note that it filters on `status = RESERVED` and marks each row `RELEASED`. That status transition is
what makes the operation naturally near-idempotent: a second release finds no `RESERVED` rows and
returns `NONE`. Not a substitute for real idempotency ([Chapter 4](../04-reliability/README.md)), but the
right shape — and it matters more here than anywhere else in the system, because *a second release
would hand the same units back to stock again, inventing inventory out of nothing.*

### Concurrency: optimistic locking

Two orders want the last two `SKU-004`s. Both read `freeQuantity() == 2`, both decide they can
proceed, both write. Stock is oversold.

This is a **check-then-act race**, and it is not solved by a transaction alone: PostgreSQL's default
`READ COMMITTED` isolation lets both transactions read the same value and both commit.

Two families of answer:

- **Pessimistic** — take a lock when you read (`SELECT … FOR UPDATE`), so the second reader waits.
  Correct, and it serializes every reader of that row, including ones that were never going to
  conflict.
- **Optimistic** — don't lock. Read a version number with the row, and at write time update only if
  the version is unchanged. If someone got there first, the update matches zero rows and you are told.

Optimistic is right when conflicts are rare, which is the normal case for inventory. JPA implements
it with one annotation:

```java
@Version
private long version;
```

Hibernate then adds the version to every `UPDATE`'s `WHERE` clause and increments it:

```sql
UPDATE inventory_items SET reserved_quantity = ?, version = 6 WHERE sku = ? AND version = 5
```

Zero rows affected means someone else committed first, and Hibernate raises
`ObjectOptimisticLockingFailureException`. **A conflict is not a corruption — it is a detection.**
Nothing was oversold; you were simply told your read is stale.

At this point in the build, the honest response is to let it surface as a `409 Conflict` to the
caller, which is a legitimate answer to "two people tried to buy the last one." The caller retries or
gives up.

> **Not yet.** Once the caller is a Kafka consumer rather than an HTTP client
> ([Chapter 3](../03-kafka-and-services/README.md)), there is nobody to hand a 409 to — the consumer must
> resolve it itself. [Chapter 4](../04-reliability/README.md) adds the retry loop, the randomized backoff,
> the 25-attempt budget and the reasoning for why that loop is guaranteed to terminate. This is the
> project's single highest-scrutiny piece of code and it deserves its own treatment rather than a
> footnote here.

---

## Payment Service — a deterministic simulator

No real provider, no card data, no money. `docs/planning/project-overview.md` rules all three out
explicitly, and the ADRs are careful never to imply otherwise.

What the simulator needs to be is **deterministic and externally controllable**, because Scenario 3
has to reject a payment on demand and get the same result every time.

```java
return switch (behavior.mode()) {
    case DEFAULT_SUCCESS -> {
        repository.save(new PaymentAttemptEntity(attemptId, orderId, idempotencyKey,
                PaymentAttemptStatus.AUTHORIZED, amount, null, now));
        yield PaymentOutcome.authorized(attemptId);
    }
    case REJECT -> {
        PaymentFailureReason reason = behavior.failureReason() != null
                ? PaymentFailureReason.valueOf(behavior.failureReason())
                : PaymentFailureReason.CARD_DECLINED;
        repository.save(new PaymentAttemptEntity(attemptId, orderId, idempotencyKey,
                PaymentAttemptStatus.REJECTED, amount, reason, now));
        yield PaymentOutcome.rejected(attemptId, reason);
    }
    case RETRYABLE_ERROR -> {
        repository.save(new PaymentAttemptEntity(attemptId, orderId, idempotencyKey,
                PaymentAttemptStatus.PENDING, amount, null, now));
        yield PaymentOutcome.providerError(attemptId);
    }
};
```

Three modes, and the third is the interesting one. `DEFAULT_SUCCESS` and `REJECT` are the two business
outcomes. **`RETRYABLE_ERROR` is a different category entirely** — not "the payment was declined" but
"we could not find out whether it was declined." That distinction drives everything in
[Chapter 4](../04-reliability/README.md): a decline is a final answer and produces an event; a provider
error is a transient failure and should be retried.

The behavior itself lives outside the request, in `PaymentBehaviorStore`:

```java
@Component
public class PaymentBehaviorStore {
    private final AtomicReference<PaymentBehaviorDto> current =
            new AtomicReference<>(PaymentBehaviorDto.defaultSuccess());

    public PaymentBehaviorDto resolveFor(String orderId) {
        PaymentBehaviorDto behavior = current.get();
        if (behavior.orderId() != null && !behavior.orderId().equals(orderId)) {
            return PaymentBehaviorDto.defaultSuccess();
        }
        return behavior;
    }
}
```

This is [ADR-002](../01-design-contract/3-state-and-api-contracts.md) in its purest form. The order
request has no `forcePaymentFailure` flag; instead the *environment* is configured through
`PUT /demo/payment-behavior`, and the business path reads it without knowing why it is set. **Failure
injection as a property of the environment, not of the request** — which is also how real operational
failures arrive.

`AtomicReference` because this is a singleton bean read concurrently by every request thread
(see the [DI primer](../technology/spring/dependency-injection.md) on singleton scope). And in-memory
on purpose: the OpenAPI spec specifies that the override does not survive a restart.

`resolveFor` supports an order-scoped override falling back to a global one. That partial scoping is
the ADR's acknowledged *"demo-only wart"* — the override is armed *before* its target order exists, so
for the duration of a run it is un-scoped, and any order created meanwhile is affected.

---

## Fulfillment Service — the terminal step

The simplest domain:

```java
String shipmentId = idGenerator.nextShipmentId();
String trackingNumber = "TRK-" + String.format("%09d", Math.abs(shipmentId.hashCode()) % 1_000_000_000);
ShipmentEntity shipment = new ShipmentEntity(shipmentId, orderId, "CREATED", trackingNumber, Instant.now());
repository.save(shipment);
```

No carrier integration; tracking numbers are generated locally and mean nothing. `shipments.order_id`
carries a `UNIQUE` constraint — one shipment per order — which
[Chapter 4](../04-reliability/README.md) later leans on as a defence-in-depth backstop behind real
idempotency.

---

## The temporary wiring

You now have four domains and no workflow. Something has to call them in order.

```java
// TEMPORARY — deleted in Chapter 3.
@Service
public class SynchronousOrderWorkflow {

    @Transactional
    public OrderDetail process(CreateOrderRequest request) {
        String orderId = orderService.createOrder(request).id();
        OrderDetail order = orderService.getOrder(orderId);

        ReservationResult reservation = inventoryService.reserve(orderId, toLines(request.items()));
        if (reservation.failed()) {
            persistence.appendStatus(orderId, OrderStatus.REJECTED_OUT_OF_STOCK, null);
            return orderService.getOrder(orderId);
        }
        persistence.appendStatus(orderId, OrderStatus.INVENTORY_RESERVED, null);
        persistence.appendStatus(orderId, OrderStatus.PAYMENT_PENDING, null);

        PaymentOutcome payment = paymentService.authorize(orderId, order.totalAmount(), UUID.randomUUID());
        if (payment.rejected()) {
            persistence.appendStatus(orderId, OrderStatus.PAYMENT_FAILED, null);
            inventoryService.release(orderId);                 // compensation
            return orderService.getOrder(orderId);
        }
        persistence.appendStatus(orderId, OrderStatus.PAID, null);
        persistence.appendStatus(orderId, OrderStatus.FULFILLMENT_PENDING, null);

        fulfillmentService.createShipment(orderId);
        persistence.appendStatus(orderId, OrderStatus.FULFILLED, null);
        return orderService.getOrder(orderId);
    }
}
```

**This class does not exist in the repository and is not meant to survive.** It is scaffolding, and it
is worth writing anyway, because reading it teaches three things that the distributed version hides.

**The whole workflow is visible in one place.** Every transition from
[Chapter 1](../01-design-contract/3-state-and-api-contracts.md)'s table, in order, in twenty lines.
Screenshot it. Once Kafka arrives, this sequence exists nowhere — it becomes an emergent property of
four consumers, and `docs/architecture-diagram.md` is the closest thing to this listing that survives.

(`appendStatus` is the real `OrderPersistence` method, minus the `eventKey` parameter
[Chapter 4](../04-reliability/README.md) adds. `sourceEventId` is null throughout, which is correct — every
transition here is internal, caused by a line of code rather than an inbound event.)

**Compensation is explicit.** `inventoryService.release(orderId)` after a rejected payment is a
*compensating action*, not a rollback. Note that it sits inside a `@Transactional` method here, which
makes it look like it could be a rollback — and that illusion is exactly what
[Chapter 3](../03-kafka-and-services/README.md) destroys. Once the four domains have four databases and four
processes, there is no shared transaction to roll back, and the compensating step is all you have.

**`POST /api/orders` returns the terminal status.** Which contradicts
`docs/openapi/order-service.yaml`, and the real project shipped exactly this deviation, documenting it
as deliberate and temporary. It is the one place where this chapter is knowingly at odds with a frozen
contract, and it is resolved in the next chapter rather than papered over.

Two more things this wiring quietly relies on, both of which disappear:

- **One transaction across four domains.** Legal in a monolith, impossible afterwards.
- **Ordering for free.** `PAID` cannot arrive before `INVENTORY_RESERVED`, because one line of code
  runs after another. Kafka provides no such guarantee across topics — the subject of ADR-009 and
  [Chapter 4](../04-reliability/README.md).

> **What survives into Chapter 3.** All four domain services, unchanged, minus the parameters that do
> not exist yet. `InventoryService.reserve(orderId, lines)` becomes
> `reserve(orderId, lines, eventKey)`; `PaymentService.authorize(...)` and
> `FulfillmentService.createShipment(...)` gain the same. That is the payoff of keeping business logic
> out of controllers and out of this orchestrator: the domain code is called from a Kafka listener
> instead of a method, and does not otherwise change.

---

[← The HTTP layer](3-the-http-layer.md) · [Next: Testing →](5-testing.md)
