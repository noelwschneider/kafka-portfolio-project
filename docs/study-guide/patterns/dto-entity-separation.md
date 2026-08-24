# Pattern — DTO / entity separation

**Where it's introduced:** [Chapter 2, section 3](../02-domain/3-the-http-layer.md).
**Where it recurs:** every controller in all five services.

---

## The rule

> Never expose JPA entities directly from controllers; keep DTOs separate from persistence entities.

It is one of this project's twenty hard agent rules and it holds without exception across the
codebase.

---

## The problem

A JPA entity is a description of a database row. A response body is a description of what a client is
entitled to see. Those are two different things that happen to overlap at the moment you write them,
and the overlap is what makes the shortcut tempting: `return orderRepository.findById(id)` works, is
one line, and produces plausible JSON.

Five things go wrong afterwards.

**Your schema becomes your public API.** Rename a column and you have broken every client. Every
future migration is now an API-versioning problem, which is precisely the coupling
[Chapter 1](../01-design-contract/1-boundaries-and-ownership.md) spent a whole ADR avoiding
*between* services — and it is no more acceptable between your database and your consumers.

**Everything is exposed, including what shouldn't be.** Serialization is opt-out. A column added for
internal bookkeeping — a version counter, an internal note, a flag — appears in the response the
moment it is added to the entity, and nobody reviewing the migration is thinking about the API.

**Lazy loading meets serialization.** With `open-in-view: false` (which is the correct setting), a
lazily-loaded association touched during JSON writing throws. With it on, the serializer silently
issues queries. Neither is a good outcome, and both are invisible until they happen.

**The request side is worse.** Binding a request body straight onto an entity means the client
chooses which fields to set. Mass-assignment vulnerabilities are exactly this shape: a field the
client should never control — `status`, `totalAmount`, an ownership reference — set from JSON because
nobody enumerated what was writable.

**Validation ends up in the wrong place.** Input constraints ("customerId is 1–64 characters") are
about *requests*, not about *rows*. Putting them on the entity means they also apply to internal
writes that should not be subject to them.

## The decision

Two families of types, in different packages, converted explicitly in the service layer.

- **Entities** (`com.orderfulfillment.<domain>`) — mutable classes, JPA-annotated, package-visible
  intent, never serialized to a client.
- **DTOs** (`com.orderfulfillment.<domain>.dto`) — immutable Java records, Bean Validation
  annotations on the inbound ones, no JPA anywhere.

Java records make the DTO side nearly free:

```java
public record OrderAccepted(String id, String status, Instant createdAt) { }

public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 64) String customerId,
        @NotEmpty @Size(min = 1, max = 20) @Valid List<CreateOrderItem> items
) { }
```

Conversion is a hand-written mapping in the service layer, not a reflective mapper library:

```java
List<OrderItemDto> items = orderItemRepository.findByOrderId(orderId).stream()
        .map(i -> new OrderItemDto(i.getSku(), i.getQuantity(), i.getUnitPrice()))
        .toList();
return new OrderDetail(order.getId(), order.getCustomerId(), order.getStatus().name(),
        order.getTotalAmount(), order.getCreatedAt(), order.getUpdatedAt(), items, history);
```

Verbose on purpose. Every field that reaches a client is named at least once by a human, which is the
property the whole pattern exists to buy. An automatic mapper would restore the coupling by making
new entity fields flow outward by default.

## Details worth copying

**`status` is a `String` in the DTO, an `OrderStatus` enum in the entity.** The conversion is
`order.getStatus().name()`. This keeps Jackson's enum handling out of the contract and means the
response shape is decided by the OpenAPI spec rather than by an enum's serialization defaults.

**Inbound and outbound DTOs are separate types.** `CreateOrderRequest` is not a stripped-down
`OrderDetail`. What a client may send and what it may receive are different questions, and one type
answering both drifts toward being neither.

**Different DTOs for different responses.** `POST /api/orders` returns `OrderAccepted` — three
fields. `GET /api/orders/{id}` returns `OrderDetail` — eight, including items and full status
history. `GET /api/orders` returns `OrderSummary` inside an `OrderPage`. A list endpoint that returned
full detail per row would fetch history for every order on the page.

**No entity type appears in any controller signature.** The mechanical way to check the pattern holds:
grep the controllers for `Entity` and expect nothing.

## The cost

Real, and worth stating plainly: more types, and a mapping to update whenever a field should become
visible. That is the trade — a small recurring cost, in exchange for the schema and the API being
able to change independently. For a system whose whole thesis is that boundaries are worth paying
for, it is a consistent one.

## Where else this appears

Every service. `InventoryItemDto` and `UpdateInventoryRequest` in Inventory Service,
`PaymentAttemptDto` and `PaymentBehaviorDto` in Payment Service, `ShipmentDto` in Fulfillment
Service, and nine DTOs in Scenario Service.

The event payloads in `common/events/` are the same idea applied to a different boundary: the wire
contract for Kafka is its own set of records, separate from any entity, for the same reasons.
See [Chapter 3](../03-kafka-and-services/README.md).
