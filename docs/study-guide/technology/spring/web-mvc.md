# Spring MVC: controllers and exception handling

*Referenced from [Chapter 2.3 — The HTTP layer](../../02-domain/3-the-http-layer.md).*

---

## Controllers

`@RestController` = `@Controller` + `@ResponseBody`. Every handler method's return value is
serialized into the response body — JSON, via Jackson — rather than resolved as a view name.

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @PostMapping
    public ResponseEntity<OrderAccepted> createOrder(@Valid @RequestBody CreateOrderRequest request) { ... }

    @GetMapping("/{orderId}")
    public OrderDetail getOrder(@PathVariable String orderId) { ... }

    @GetMapping
    public OrderPage listOrders(@RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "0") int page) { ... }
}
```

| Annotation | Binds |
|---|---|
| `@RequestMapping` on the class | A path prefix every method inherits |
| `@GetMapping` / `@PostMapping` / … | Method + path |
| `@RequestBody` | The deserialized request body |
| `@PathVariable` | A `{placeholder}` path segment |
| `@RequestParam` | A query parameter (`required`, `defaultValue`) |
| `@RequestHeader` | A header value |
| `@Valid` | Triggers Bean Validation before the method body runs |

### Path matching precedence

Literal segments beat variables, regardless of declaration order. `/api/orders/stream` resolves to a
`@GetMapping("/stream")` even if `@GetMapping("/{orderId}")` is declared first. Relying on that is
correct but not obvious, so declaring the literal route first anyway is worth doing as documentation.

### Return types

Return the DTO directly for a plain `200 OK`. Return `ResponseEntity<T>` when you need to control the
status or headers:

```java
return ResponseEntity.created(URI.create("/api/orders/" + id)).body(accepted);
```

`201 Created` plus a `Location` header. Also available: `ResponseEntity.noContent()` (204),
`.accepted()` (202), `.status(...)` for anything else.

**`202 Accepted` deserves a mention.** It means "I have taken this and will process it; the outcome is
not known yet" — literally the semantics of an asynchronous workflow. `201 Created` is the right
choice when a resource genuinely now exists to be fetched, which is why this project uses it; `202` is
the right choice when it does not.

### What belongs in a controller

Bind input, call one service method, shape the response. **No business logic.**

Not a style rule. Logic in a controller is reachable only from an HTTP request — it cannot be reused
by a message consumer or a scheduled job, and it can only be tested by standing up a web layer.

---

## Exception handling

### The problem

Errors reach a client from places that know nothing about each other: your code throwing
deliberately; the validation layer; Jackson failing to parse a body; the dispatcher finding no
handler; anything throwing unexpectedly. Left alone, each produces a differently-shaped response, and
some leak stack traces.

### `@RestControllerAdvice`

Registers exception handlers across every controller in the application.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }
}
```

Handlers are matched **most-specific-first**, so a catch-all `@ExceptionHandler(Exception.class)`
coexists safely with narrower ones.

### The exceptions worth handling explicitly

Everything not handled falls through to the catch-all and is reported as **500** — which is wrong for
several very common cases. These four are the ones most projects miss:

| Exception | Thrown when | Correct status |
|---|---|---|
| `MethodArgumentNotValidException` | `@Valid` fails | **400** |
| `HttpMessageNotReadableException` | The body is malformed JSON | **400** |
| `NoResourceFoundException` | No handler matches the path | **404** |
| `HttpRequestMethodNotSupportedException` | Path exists, wrong HTTP method | **405** |

The last two are the sneaky ones. A request for a URL that does not exist is not a server error, and
neither is a `POST` to a `GET`-only route. Reported as 500s they also get logged at `ERROR`, which
turns ordinary client mistakes into noise in the logs you would use to find real failures.

### The catch-all, done correctly

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
}
```

Two halves, both required:

- **Tell the client nothing.** No exception type, no message, no stack trace — those describe your
  internals and are an information-disclosure risk.
- **Log everything**, at `ERROR`, with the exception passed as the last argument so the stack trace
  is attached.

Getting the second half wrong produces the worst possible outcome: a 500 that appears in no log. The
client knows something broke and you have no way to find out what.

### A handler that returns `void`

Occasionally the right response is *no response at all*:

```java
@ExceptionHandler(AsyncRequestNotUsableException.class)
public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex, HttpServletRequest request) {
    log.debug("Dropping unusable async request — client connection is already gone", ex);
}
```

A `void` return tells Spring MVC the response is fully handled and it should write nothing. This is
the correct shape when the connection is already broken, or when the response is already committed
with a `Content-Type` no error body can be written into — a long-lived `text/event-stream`, for
instance. Attempting to write a JSON error onto a committed SSE response fails a second time, on top
of the original failure.

---

## Content negotiation and Jackson

`spring-boot-starter-web` configures Jackson for JSON automatically. Worth knowing:

- **Java records serialize cleanly** — component names become field names. This is why records make
  such good DTOs.
- **`Instant` serializes as ISO-8601** when `jackson-datatype-jsr310` is present, which the starter
  includes. Without it you get an epoch number, which is a common and ugly surprise.
- **Unknown fields on deserialization** fail by default. Turning that off
  (`fail-on-unknown-properties: false`) is what lets a consumer tolerate a message written by a newer
  producer — an explicit requirement of this project's event versioning rule.
