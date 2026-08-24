# OpenAPI

*Referenced from [Chapter 1.3 — The state and API contracts](../../01-design-contract/3-state-and-api-contracts.md).*

---

## What it is

A YAML (or JSON) description of an HTTP API: paths, methods, parameters, request and response schemas,
status codes, and prose. Formerly called Swagger, which still names much of the tooling.

```yaml
openapi: 3.1.0

info:
  title: Order Service API
  version: 1.0.0

paths:
  /api/orders:
    post:
      operationId: createOrder
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateOrderRequest'
      responses:
        '201':
          description: Order accepted
```

## Two ways to use it, and they are not the same

**Generated from code.** Annotate your controllers, and a library produces the spec from what the
code does. The spec is always accurate and is pure *documentation* — it describes something that
already exists.

**Written first, code built against it.** The spec is a *contract*. It constrains something that does
not exist yet, which means a frontend and a backend can be built simultaneously by people who never
speak, and either can be tested against the spec rather than against the other.

The second is more work and buys something the first cannot: **on disagreement, there is an
authority.** If generated docs disagree with a client's expectation, the docs are right by
construction and the client is out of luck. If a written contract disagrees with an implementation,
the implementation has a bug — or the contract gets changed deliberately, and everything downstream is
re-checked.

Pick one deliberately. Generated specs are right for an API whose clients you control and ship
together. Written specs are right when the boundary is real.

## What a schema cannot say

The most valuable content in a hand-written spec is usually prose, because the things that most often
surprise a client are not shape:

> **Asynchrony.** `POST /api/orders` returns as soon as the order is persisted and `OrderCreated` is
> published. It does not wait for inventory, payment, or fulfillment. Clients observe the rest of the
> lifecycle by polling `GET /api/orders/{orderId}` or subscribing to `GET /api/orders/stream`.

No schema expresses that. A client author reading only the response shape sees an order with a
`status` field and reasonably concludes the status is *the answer*. It is the *first* answer.

Also worth writing down explicitly:

- **Where the document stops.** "Health and metrics are exposed by Actuator and are deliberately
  outside this document."
- **How it may change.** A frozen contract should say so, and say what the process is.
- **Which paths are what.** If some endpoints are production API and others are demo controls, the
  document should make the split unmissable.

## Useful mechanics

**`$ref` and `components/schemas`.** Define a type once, reference it everywhere. Shared error
envelopes especially — `ApiError` appears in every error response of every path.

**`operationId`.** A unique name per operation. Code generators use it for method names; a missing or
duplicated one produces generated clients with names like `postApiOrders1`.

**`servers`.** Where the API lives, including local development. Useful for mock servers and for
"try it" UIs.

**Examples.** Realistic request and response examples are worth more per line than almost anything
else in the file, especially for anyone reading rather than generating.

## The trap

**A hand-written spec can drift from the implementation, silently.** Nothing enforces the relationship
by default — that is exactly what makes it a contract rather than documentation, and exactly what makes
it able to lie.

Defences, in increasing order of effort: cite the spec from the code (a Javadoc line naming the file
and section, so anyone editing knows where the authority lives); assert response shapes in integration
tests; or generate a client from the spec and use it *in* the tests, so a divergence fails the build.
