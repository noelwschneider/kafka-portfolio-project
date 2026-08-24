# Bean Validation

*Referenced from [Chapter 2.3 — The HTTP layer](../../02-domain/3-the-http-layer.md).*

Jakarta Bean Validation (JSR 380), implemented by Hibernate Validator. Annotation-driven constraint
checking on objects.

---

## How it fits together

You annotate fields. Something walks the object and collects violations. In Spring MVC, that
"something" is triggered by `@Valid` on a handler parameter, and the violations become a
`MethodArgumentNotValidException` before your method body ever runs.

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

## The constraints you'll actually use

| Annotation | Applies to | Checks |
|---|---|---|
| `@NotNull` | anything | not null |
| `@NotEmpty` | String, Collection, Map, array | not null **and** not empty |
| `@NotBlank` | String | not null and contains non-whitespace |
| `@Size(min, max)` | String, Collection, Map, array | length or size in range |
| `@Min` / `@Max` | numbers | value in range |
| `@Positive` / `@PositiveOrZero` | numbers | sign |
| `@Pattern(regexp)` | String | matches |
| `@Email` | String | plausible email shape |
| `@Past` / `@Future` | temporal types | relative to now |
| `@Valid` | nested object or collection | **recurse into it** |

## Four things that catch people out

### `@Valid` on a collection is what makes it recurse

```java
@NotEmpty @Size(min = 1, max = 20) @Valid List<CreateOrderItem> items
```

Without `@Valid`, the list is checked for emptiness and size, and **the constraints on each element
are never evaluated**. Nested validation is opt-in. This is a quiet bug: the endpoint appears
validated, and element-level rules do nothing.

### Use boxed types, not primitives

```java
@NotNull @Min(1) Integer quantity     // good
@Min(1) int quantity                  // subtly wrong
```

A primitive `int` cannot be null, so an absent field silently becomes `0` and then fails `@Min(1)`
with a message about the value being too small rather than about the field being missing. Boxing lets
`@NotNull` distinguish "absent" from "zero."

### Bound everything that comes from outside

Every string and every collection from an untrusted source needs an upper bound — `max = 64`,
`max = 20`, `@Max(100)`. Not because the specific number is meaningful, but because an unbounded
input is a resource-consumption problem. A request with 500,000 items would otherwise be dutifully
parsed, priced, and inserted.

### Validate shape here, business rules elsewhere

`@Pattern(regexp = "^SKU-[0-9]{3}$")` checks that a SKU is *shaped* like a SKU. Whether `SKU-999` is
a real product is a different question, requiring domain data, and it belongs in the service layer —
throwing a domain exception with its own error code, not a validation annotation.

The same goes for cross-field invariants ("a SKU may appear at most once per order"). Bean Validation
can express these with a custom class-level constraint, but a plain check in the service is usually
clearer and keeps the rule where the rule lives.

## Reporting violations

`MethodArgumentNotValidException` carries a `BindingResult` with every field error. Two reasonable
strategies:

```java
// One error, simple
String message = ex.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
        .orElse("Validation failed");

// All errors, better for form-driven clients
List<FieldError> all = ex.getBindingResult().getFieldErrors();
```

Reporting only the first is a legitimate simplification for an API whose clients submit one field at
a time; a form UI generally wants all of them so it can mark every bad field at once. Pick
deliberately — it is part of your API contract either way.

## Validating outside controllers

`@Valid` on a controller parameter is Spring MVC's integration. To validate elsewhere:

- **`@Validated` on a `@Service` class** plus constraints on method parameters — Spring proxies the
  bean and validates on call, throwing `ConstraintViolationException`.
- **Inject a `Validator`** and call `validator.validate(obj)` for full manual control.

Note the different exception type: `ConstraintViolationException`, not
`MethodArgumentNotValidException`. If you validate in the service layer, your exception handler needs
a case for it, or those failures become 500s.
