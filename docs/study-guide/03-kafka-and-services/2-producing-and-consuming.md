# 3.2 — Producing and consuming

[← Events on the wire](1-events-on-the-wire.md) · [Next: Correlation IDs →](3-correlation-ids.md)

Deleting `SynchronousOrderWorkflow` and letting the workflow emerge from four listeners instead.

---

## The shape of a consumer

Every `@KafkaListener` in this project has the same four-step structure. Learn it once:

```java
@KafkaListener(id = InventoryConsumers.ORDER_CREATED_LISTENER_ID,
        topics = KafkaTopics.ORDERS_EVENTS, groupId = GROUP_ID)
public void onMessage(String message) {
    EventEnvelope<JsonNode> envelope = eventCodec.decode(message);          // 1. decode envelope
    CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));  // 2. enter scope
}

private void handle(EventEnvelope<JsonNode> envelope) {
    if (!EventTypes.ORDER_CREATED.equals(envelope.eventType())) {           // 3. is this ours?
        return;
    }
    OrderCreatedPayload payload = eventCodec.payloadAs(envelope, OrderCreatedPayload.class);
    inventoryService.reserve(payload.orderId(), toLines(payload.items()));  // 4. delegate to the domain
}
```

1. **Decode the envelope**, payload left as `JsonNode`.
2. **Enter correlation scope** ([section 3](3-correlation-ids.md)).
3. **Filter by `eventType`** — because a topic carries more than one, and most listeners want a
   subset.
4. **Convert the payload and call the domain service.** The listener itself contains no business
   logic.

Step 4 is the payoff from [Chapter 2](../02-domain/4-the-four-domains.md). `InventoryService.reserve`
does not change when its caller becomes a Kafka listener instead of an orchestrator — because it never
knew about its caller in the first place. A listener is a **second entry point** into the same domain,
exactly as a controller is.

### The `groupId`

```java
private static final String GROUP_ID = "inventory-service";
```

One group per service. That is what makes fan-out work: Order Service and Fulfillment Service both
consume `payments.events`, in **different** groups, so each receives every record independently and
neither knows the other exists. Put them in one group and they would split the partitions and each see
roughly half — a subtle, data-losing bug with no error attached.

### The `id`

```java
static final String INVENTORY_EVENTS_LISTENER_ID = "inventory-events";
```

A stable name for the listener *container*, distinct from the group ID. This is what makes a listener
addressable at runtime through `KafkaListenerEndpointRegistry` — which is how
[Chapter 4](../04-reliability/README.md) pauses a consumer for Scenario 5. Give every listener an explicit
`id`; the auto-generated ones are not stable across restarts.

`OrderConsumers` and `InventoryConsumers` collect these as compile-time constants, and the Javadoc
explains why the two namespaces are kept apart:

> the **listener id** is the `@KafkaListener` id — one per inbound topic [...] the **consumer name**
> is the `processed_events.consumer_name` column, qualified by service (`"order.inventory-events"`).
>
> Both are compile-time constants and must never be derived from anything that varies between
> restarts: a ledger row written under one name and looked up under another would not deduplicate.

The second namespace belongs to [Chapter 4](../04-reliability/README.md) — but define both now, because
"never derived from anything that varies between restarts" is much easier to honour from the start
than to retrofit.

---

## The workflow, redistributed

Here is the whole of `SynchronousOrderWorkflow`, redistributed across four services. Nothing calls
anything.

### Order Service — `POST /api/orders`

Persists the order as `PENDING` and publishes `OrderCreated` to `orders.events`. Returns. **This is
where the HTTP request ends**, and where the OpenAPI spec's asynchrony note finally becomes true:

```java
/**
 * Entry point for POST /api/orders. Persists the order as PENDING, records OrderCreated for
 * publication, and returns — it does not wait for inventory, payment, or fulfillment. That
 * happens because Inventory/Payment/Fulfillment now react to Kafka events rather than being
 * called directly from here, so this class no longer knows or cares how the order eventually
 * resolves. This finally matches docs/openapi/order-service.yaml's POST /api/orders description;
 * Phase 1's synchronous version (which returned the actual terminal status) is documented as a
 * deliberate, temporary deviation.
 */
```

*"no longer knows or cares how the order eventually resolves"* is the sentence to hold onto. It is the
entire architectural change in eight words.

### Inventory Service — consuming `orders.events`

Reacts to `OrderCreated`, reserves, publishes `InventoryReserved` or `InventoryReservationFailed` to
`inventory.events`.

Note the filter, and the comment on it:

```java
// orders.events also carries PaymentRequested, which Inventory Service has no use for.
if (!EventTypes.ORDER_CREATED.equals(envelope.eventType())) {
    return;
}
```

This is the cost of domain-oriented topics from
[Chapter 1](../01-design-contract/2-the-event-contract.md): a consumer sees everything on the topic
and discards what is not its business. Cheap — a string comparison per record — and the price of
having four topics instead of eight.

### Order Service — consuming `inventory.events`

Drives transitions 2 and 3, and on success transition 4 (publishing `PaymentRequested`).

Note the `switch` and its `default`:

```java
private void handle(EventEnvelope<JsonNode> envelope) {
    switch (envelope.eventType()) {
        case EventTypes.INVENTORY_RESERVED -> onInventoryReserved(envelope);
        case EventTypes.INVENTORY_RESERVATION_FAILED -> onInventoryReservationFailed(envelope);
        // InventoryReleased has no Order Service consumer in v1 (event-catalog.md §3) — ignored,
        // and filtered here before the ledger is touched: a skipped record has no side effect to
        // deduplicate.
        default -> { /* not one of ours */ }
    }
}
```

The `default` branch is doing something worth naming: it makes **an unrecognized event type a no-op
rather than an error**. That is what allows a new event type to be added to a topic without breaking
every existing consumer — the same forward-compatibility idea as ignoring unknown JSON fields, one
level up.

### Payment Service — consuming `orders.events`

Reacts to `PaymentRequested`, runs the simulator, publishes `PaymentAuthorized` or `PaymentRejected`
to `payments.events`.

Note that Payment Service and Inventory Service **both** consume `orders.events`, in different groups,
each filtering for a different event type. Neither is aware of the other.

### Three consumers of `payments.events`

This is the most interesting topic in the system, and the clearest illustration of what a log buys
you:

- **Order Service** consumes `PaymentAuthorized` → `PAID` → `FULFILLMENT_PENDING`, and
  `PaymentRejected` → `PAYMENT_FAILED`.
- **Fulfillment Service** consumes `PaymentAuthorized` → creates a shipment → publishes
  `ShipmentCreated`.
- **Inventory Service** consumes `PaymentRejected` → releases the reservation → publishes
  `InventoryReleased`.

**One record, three independent readers, three unrelated reactions, zero coordination.** Payment
Service knows about none of them. Adding a fourth — a notification service, say — would require
nothing from Payment Service at all.

That is the fan-out ADR-001 cited as a benefit: *"`PaymentAuthorized` is consumed by two services in
different consumer groups for different reasons, with neither aware of the other — fan-out that costs
nothing to add."*

It is also where the compensation path lives. Inventory releasing stock on `PaymentRejected` is the
compensating action from [Chapter 2](../02-domain/4-the-four-domains.md) — except now there is no
shared transaction it could have been part of, which was always the point.

### Order Service — consuming `fulfillment.events`

`ShipmentCreated` → `FULFILLED`. Terminal.

---

## What just got harder

Four things the monolith gave you for free are now gone. Naming them is most of what this chapter is
for.

**The workflow is not written down anywhere.** There is no file you can read to learn the sequence.
It is an emergent property of four listeners' subscriptions. `docs/architecture-diagram.md` is the
closest surviving artifact, and it is a diagram rather than code — which is exactly why Phase 0
insisted on producing it.

**A transaction covers one service, not the workflow.** Inventory's reservation commits whether or not
payment later succeeds. Undoing it requires an explicit compensating event, and there is a window
during which stock is reserved for an order that is about to fail.

**Ordering guarantees are much weaker than they look.** Keying by `orderId` orders one order's records
within *one topic's* partition. Order Service consumes **three** topics, and Kafka guarantees nothing
between them.

> **Not yet — and this one bites hard.** Order Service consumes `payments.events` and
> `fulfillment.events` independently. `ShipmentCreated` can be processed *before* the
> `PaymentAuthorized` that caused it, because they are on different topics with different partitions
> and different offsets. At this point in the build nothing prevents that.
> [Chapter 4](../04-reliability/README.md) builds the guard (ADR-009);
> [Chapter 10](../10-retrospective/README.md) has the story of finding it in Phase 10, live, under load.

**Duplicate delivery is now normal.** At-least-once means every consumer will eventually see the same
record twice — from an uncommitted offset after a crash, or a rebalance mid-batch.

> **Not yet.** Right now a redelivered `OrderCreated` reserves stock a second time. The
> `ProcessedEventLedger` in the real consumers is [Chapter 4](../04-reliability/README.md); build these
> listeners without it and you will be able to demonstrate the problem before building the fix, which
> is worth doing at least once.

---

## Verifying it

At the end of this section, `POST /api/orders` should return `PENDING` immediately and the order
should reach `FULFILLED` a moment later without anything calling anything.

Two things worth watching while you get there:

**Consumer group state.** `kafka-consumer-groups.sh --describe --group inventory-service` shows
partition assignment, current offset, and lag. If a consumer is receiving nothing, this is where you
look first — and `auto-offset-reset: latest` (the Kafka default, which this project overrides to
`earliest`) is the single most common reason.

**The topics themselves.** `kafka-console-consumer.sh --topic orders.events --from-beginning` prints
the actual JSON envelopes. Seeing your own frozen envelope come back off the wire is the fastest way
to confirm that [section 1](1-events-on-the-wire.md) is wired correctly.

---

[← Events on the wire](1-events-on-the-wire.md) · [Next: Correlation IDs →](3-correlation-ids.md)
