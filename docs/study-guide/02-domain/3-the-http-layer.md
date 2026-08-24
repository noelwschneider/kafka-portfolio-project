# 2.3 — The HTTP layer

[← Persistence](2-persistence.md) · [Next: The four domains →](4-the-four-domains.md)

Controllers, the DTOs they speak in, the validation that rejects bad input, and one error model
shared by all five services.

---

## Controllers

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderAccepted> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderAccepted accepted = orderService.createOrder(request);
        log.info("Order {} created", accepted.id());
        return ResponseEntity.created(URI.create("/api/orders/" + accepted.id())).body(accepted);
    }

    @GetMapping
    public OrderPage listOrders(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String customerId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return orderService.listOrders(status, customerId, page, size);
    }

    @GetMapping("/{orderId}")
    public OrderDetail getOrder(@PathVariable String orderId) {
        return orderService.getOrder(orderId);
    }
}
```

> **Primer — [Spring MVC controllers and exception handling](../technology/spring/web-mvc.md)**
> The annotation set, path-matching precedence, return types and status codes, `@RestControllerAdvice`,
> the four exceptions most projects mishandle, and the case for a `void` exception handler.

Three things about *this* controller are worth pulling out.

**The path starts with `/api`,** which is [ADR-002](../01-design-contract/3-state-and-api-contracts.md)
made physical. Demo endpoints live in an entirely separate controller class under `/demo`, in every
service that has them.

**It contains no business logic.** It binds input, calls one service method, and shapes the response.
Every rule — pricing, duplicate SKUs, what a valid status filter is — lives in `OrderService`. That is
not tidiness: logic in a controller is reachable only from an HTTP request, so it could not be reused
by the Kafka consumers of [Chapter 3](../03-kafka-and-services/README.md).

**`201 Created`, not `200 OK`.** For this API that is more than protocol correctness — it is the
honest status code. `POST /api/orders` does not return the outcome of the order; it returns that the
order now exists, in `PENDING`, and that everything interesting happens later. `200 OK` would suggest
the work is done. `201` plus a `Location` header says what actually happened, and matches the OpenAPI
spec's asynchrony note word for word.

---

## DTOs

`CreateOrderRequest` in; `OrderAccepted`, `OrderDetail`, and `OrderPage` out. All Java records, all in
a `dto` package, none of them ever a JPA entity.

This is a recurring pattern with a page of its own:

> **Pattern — [DTO / entity separation](../patterns/dto-entity-separation.md)**
> Why entities are never returned from a controller, the five things that go wrong when they are, why
> the mapping is hand-written rather than reflective, and where else it applies.

Read it now — the rest of the guide assumes it.

---

## Validation

```java
public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 64) String customerId,
        @NotEmpty @Size(min = 1, max = 20) @Valid List<CreateOrderItem> items
) { }

public record CreateOrderItem(
        @NotNull @Pattern(regexp = "^SKU-[0-9]{3}$") String sku,
        @NotNull @Min(1) @Max(100) Integer quantity
) { }
```

> **Primer — [Bean Validation](../technology/spring/bean-validation.md)**
> The constraint vocabulary, why `@Valid` on a collection is what makes it recurse, why boxed types
> rather than primitives, bounding untrusted input, and validating outside controllers.

### Where validation is *not*

Two checks in `OrderService` that no annotation could express:

```java
private void validateNoDuplicateSkus(List<CreateOrderItem> items) {
    long distinctCount = items.stream().map(CreateOrderItem::sku).distinct().count();
    if (distinctCount != items.size()) {
        throw new ValidationApiException("INVALID_ORDER", "A SKU may appear at most once per order");
    }
}
```

```java
BigDecimal price = priceCatalog.priceFor(item.sku());
if (price == null) {
    throw new ValidationApiException("UNKNOWN_SKU", "No price known for SKU " + item.sku());
}
```

The first is a cross-field invariant; the second needs domain data. Both are **business rules that
happen to produce a 400**, and both live where the rule lives, throwing a shared exception type the
HTTP layer knows how to render.

Note the division of labour on duplicates: `@Pattern` checks a SKU is *shaped* like a SKU;
`priceCatalog` checks it *exists*; `UNIQUE (order_id, sku)` in the schema guarantees uniqueness; and
`validateNoDuplicateSkus` exists purely so the client gets a readable message instead of a constraint
violation. Four layers, each doing a different job.

---

## The error model

Errors reach a client from places that know nothing about each other — your code throwing
deliberately, the validation layer, Jackson failing to parse, the dispatcher finding no handler, and
anything unexpected. Left alone each produces a differently-shaped response, and some leak stack
traces. One envelope, defined once in `common`, is shared by all five services:

```java
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        UUID correlationId
) { }
```

Two fields carry more weight than they look.

**`code`** is a *stable machine-readable* string — `ORDER_NOT_FOUND`, `UNKNOWN_SKU`,
`VALIDATION_ERROR`. `message` is for humans and may be reworded freely; `code` is part of the contract
and clients may branch on it. Having both means never choosing between a good error message and a
parseable one.

**`correlationId`** puts the request's trace identifier **in the error body**. When someone reports a
failure, the response they are looking at contains the exact value that finds every log line across
every service for that operation. Highest value per line in the whole error model, and it costs one
field.

### The exception hierarchy

```java
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
}

public class NotFoundException      extends ApiException { /* 404 */ }
public class ValidationApiException extends ApiException { /* 400 */ }
public class ConflictException      extends ApiException { /* 409 */ }
```

The status lives **on the exception**, so the throw site decides the outcome and no handler needs a
mapping table:

```java
orderRepository.findById(orderId)
        .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "No order with id " + orderId));
```

Unchecked on purpose — these propagate through layers that have nothing useful to do with them, and
checked exceptions would force every one of those layers to declare or wrap them.

### The handler

One `@RestControllerAdvice` in `common`, applying to every controller in every service. Build all of
these from the start:

| Handler for | Produces | Why it must exist |
|---|---|---|
| `ApiException` | its own status + code | The deliberate case |
| `MethodArgumentNotValidException` | 400 `VALIDATION_ERROR` | What `@Valid` throws |
| `HttpMessageNotReadableException` | 400 `VALIDATION_ERROR` | Malformed JSON body |
| `NoResourceFoundException` | **404** `NOT_FOUND` | Otherwise a nonexistent URL is a **500** |
| `HttpRequestMethodNotSupportedException` | **405** | Otherwise a wrong-method request is a **500** |
| `Exception` | 500 `INTERNAL_ERROR` | Catch-all |

The validation handler reports only the **first** field error — a deliberate simplification worth
knowing you made, since a form-driven client generally wants all of them at once.

> **We got this wrong.** The 404 and 405 cases were missing for most of this project's life. Both fell
> through to the catch-all, were reported as 500s, *and were logged at `ERROR`* — turning ordinary
> client mistakes into noise in the logs you would use to find real failures. Found during Sprint 2's
> bug hunt by making the requests, not by reading the code.
> [Chapter 10](../10-retrospective/README.md).

The catch-all has two halves and both are required:

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
}
```

**Tell the client nothing** beyond "unexpected server error" — no type, no message, no stack trace,
because those describe your internals. **Log everything**, at `ERROR`, with the exception attached.
The code comment records what happens when you get the second half wrong:

> this handler previously discarded the exception entirely — a 500 left zero trace anywhere, in any
> service's logs, which defeats the whole point of correlation-id tracing.

A 500 that appears in no log is the worst possible outcome: the client knows something broke and you
have no way to find out what.

> **Not yet.** The real handler has a seventh case, `AsyncRequestNotUsableException`, which exists
> only because of SSE. There is no SSE until [Chapter 5](../05-scenarios-and-frontend/README.md), where it is
> built — and [Chapter 10](../10-retrospective/README.md) has the story, because it was a live bug on the
> deployed demo.

---

## CORS

The frontend runs on a different origin from the services (`localhost:5173` against `localhost:8081`),
so the services must opt in to being read cross-origin. `WebConfig` does this, driven by one
configuration value:

```yaml
app:
  cors:
    allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}
```

Configured rather than hard-coded — the same discipline from
[section 1](1-project-skeleton.md) — which is what lets [Chapter 9](../09-production/README.md) point the
deployed frontend at a real hostname with no code change. The comment adds the rule that matters:
*"Never `*`, never combined with credentials."*

> **Primer — [CORS](../technology/http/cors.md)**
> What the same-origin policy actually protects, preflight requests, why "it works in `curl`" proves
> nothing, the Actuator handler-mapping trap, and when a reverse proxy is the better answer.

---

[← Persistence](2-persistence.md) · [Next: The four domains →](4-the-four-domains.md)
