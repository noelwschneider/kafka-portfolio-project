# 1.3 — The state and API contracts

[← The event contract](2-the-event-contract.md) · [Next: Sequencing and deferrals →](4-sequencing-and-deferrals.md)

Two more Phase 0 artifacts. `docs/order-state-machine.md` defines what an order can be and how it
gets there. `docs/openapi/*.yaml` defines what a client can ask for. Both are frozen; both are
enforced in code later.

---

## Part A — The state machine

### The problem: status is the one thing everybody reads

Almost nothing in this system is shared. Each service owns its own tables, publishes its own events,
and minds its own business. Order status is the exception: it is written by one service and read by
everything — the UI renders it, every integration test asserts on it, every scenario's success
condition is expressed in terms of it, and the ADRs argue about it.

That makes it the single highest-value thing to get exactly right up front, and the single most
expensive thing to change later.

Two failure modes if you don't:

**Vocabulary drift.** Two documents call the same state different names; two services implement both;
a test asserts one and the UI displays the other. Nobody notices until a scenario fails for a reason
that has nothing to do with the scenario.

**Silent invalid states.** Without an explicit set of legal transitions, "what states can an order be
in, and how did it get here" has no answer other than reading every writer. Any writer can put an
order into any state, and there is no place to notice that it shouldn't have.

### The technology: a finite state machine

Four parts: a finite set of **states**, a designated **initial** state, a set of **terminal** states
with no exit, and a set of legal **transitions** — `(from, to)` pairs, each with a named cause.

The power is entirely in what it **forbids**. If the transition set is exhaustive, any pair not in it
is invalid by definition, and "invalid" becomes something code can detect rather than something a
reviewer might notice.

> **Primer — [Finite state machines](../technology/concepts/state-machines.md)**
> Why an explicit transition set matters, encoding the table so code actually consults it, the two
> consistency checks worth running on the table itself, marking internal vs. externally-caused
> transitions, and the difference between *rejecting* and *deferring* an invalid transition.

### The decision: nine states, nine transitions, every cause named

The states are in [Chapter 0](../00-orientation.md#4-the-order-states). The transitions are the
interesting half:

| # | From | To | Cause | Kind |
|---|---|---|---|---|
| 1 | *(none)* | `PENDING` | `POST /api/orders` succeeded | REST |
| 2 | `PENDING` | `INVENTORY_RESERVED` | `InventoryReserved` | event |
| 3 | `PENDING` | `REJECTED_OUT_OF_STOCK` | `InventoryReservationFailed` | event |
| 4 | `INVENTORY_RESERVED` | `PAYMENT_PENDING` | Order Service publishes `PaymentRequested` | **internal** |
| 5 | `PAYMENT_PENDING` | `PAID` | `PaymentAuthorized` | event |
| 6 | `PAYMENT_PENDING` | `PAYMENT_FAILED` | `PaymentRejected` | event |
| 7 | `PAID` | `FULFILLMENT_PENDING` | Order Service records a shipment is outstanding | **internal** |
| 8 | `FULFILLMENT_PENDING` | `FULFILLED` | `ShipmentCreated` | event |
| 9 | any non-terminal | `FAILED` | Non-retryable failure, or retries exhausted into a DLQ | **internal** |

And the rule that gives the table teeth:

> Any transition not listed above is invalid and must be rejected by the domain model, not silently
> applied.

**Event-caused vs. internal** is a distinction worth having explicitly. Six transitions happen because
Order Service consumed a named event. Three happen because Order Service moved the order itself, with
no inbound event — and those three are exactly the ones a reader would otherwise waste time hunting
for an event to explain. Marking them is a small act of kindness toward your future self.

Transition 7 is the one that surprises people, and its explanation is a good example of a contract
doing real work:

> Fulfillment Service consumes `PaymentAuthorized` directly, so Order Service never sends it a
> request. [...] one event (`PaymentAuthorized`) therefore drives two consecutive transitions in the
> same Order Service handler — `PAID` records the payment outcome, `FULFILLMENT_PENDING` records that
> a shipment is outstanding. `PAID` is consequently short-lived and will rarely be observed by the UI.

So a state exists in the enum that the UI will almost never display, because two transitions fire back
to back inside one handler. Without that note, the first person to watch the UI would file a bug.

### Consistency checks: the part most people skip

`docs/order-state-machine.md` §4 does something Phase 0 did not strictly have to do, and it is the
part worth copying. It checks the contract against itself, twice:

**Every state is reachable.** A table mapping each of the nine states to the transition that produces
it. Trivially mechanical — and it catches the classic error of an enum value nobody can actually get
into.

**Every status-changing event is accounted for.** A table mapping each of the eight catalogued events
to its status effect. Two of them (`OrderCreated`, `InventoryReleased`) have *no* status effect, and
they are listed anyway, with the reason stated:

> The two with no status effect are listed explicitly so that "missing from the transition table"
> cannot be mistaken for an oversight.

Recording a deliberate absence so it cannot be mistaken for a gap is a habit that pays off every time
someone new reads the document — including you, later.

### What freezing actually resolved

The clearest evidence that this was worth doing is a naming conflict Phase 0 caught. Three planning
documents disagreed:

- `backend-design.md`'s state list said `OUT_OF_STOCK`.
- `backend-design.md`'s own flow diagram said `REJECTED_OUT_OF_STOCK`.
- `frontend-design.md`'s Scenario 2 said `REJECTED_OUT_OF_STOCK`.

A single document contradicted itself. Phase 0 froze `REJECTED_OUT_OF_STOCK` — matching two of three
references, including the one the UI and the Scenario 2 test assert against — and *did not edit the
planning docs*, recording the conflict and its resolution in the state machine document instead.

Had this not been caught in Phase 0, it would have been caught in Phase 5 by a UI that renders a
status string no backend ever emits, and the fix would have touched an enum, a database column, a
test suite, and a frontend.

### What this becomes in code

`services/order-service/.../OrderStatus.java` is the enum, with terminality as a first-class property:

```java
/** docs/order-state-machine.md §1 — the frozen order lifecycle enum. Owned exclusively by Order Service. */
public enum OrderStatus {
    PENDING, INVENTORY_RESERVED, REJECTED_OUT_OF_STOCK, PAYMENT_PENDING,
    PAID, PAYMENT_FAILED, FULFILLMENT_PENDING, FULFILLED, FAILED;

    private static final Set<OrderStatus> TERMINAL =
            Set.of(REJECTED_OUT_OF_STOCK, PAYMENT_FAILED, FULFILLED, FAILED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
}
```

And `OrderTransitions.java` is §3's table, transcribed with the table's row numbers left in as
comments so the two can be diffed by eye:

```java
// 2, 3
VALID_PREDECESSORS.put(OrderStatus.INVENTORY_RESERVED, Set.of(OrderStatus.PENDING));
VALID_PREDECESSORS.put(OrderStatus.REJECTED_OUT_OF_STOCK, Set.of(OrderStatus.PENDING));
// 4
VALID_PREDECESSORS.put(OrderStatus.PAYMENT_PENDING, Set.of(OrderStatus.INVENTORY_RESERVED));
```

> **We got this wrong.** `OrderTransitions` did not exist until well after Phase 10. For most of the
> project's life the transition table was prose and documentation comments, and *no code consulted
> it* — `OrderPersistence` wrote whatever status its caller handed it. The bug that produced is in
> [Chapter 10](../10-retrospective/README.md); the mechanism is built properly in
> [Chapter 4](../04-reliability/README.md). The build-along writes the guard from the start.

---

## Part B — The API contracts

### OpenAPI, and what it is for

OpenAPI is a YAML description of an HTTP API: paths, methods, parameters, schemas, status codes.
Tooling can generate clients, servers, and documentation from it — and none of that tooling is why it
is here.

It is here because it is **a contract readable by someone who is not going to read your controller**,
and in this project's case by a different agent session building the frontend against a backend that
did not yet exist. That only works if the spec is *written first* and the code built against it, which
is a different activity from generating a spec out of finished code.

> **Primer — [OpenAPI](../technology/http/openapi.md)**
> Generated-from-code vs. written-first and why the distinction matters, what a schema cannot say,
> `$ref` / `operationId` / `servers`, and how a hand-written spec drifts.

The specs carry their status in the description block:

> **Frozen by Phase 0.** Changes must follow the coordination protocol [...]: propose the change in
> this file first with a rationale, then update implementations and tests.

They also carry the things a schema cannot express. From `order-service.yaml`:

> **Asynchrony.** `POST /api/orders` returns as soon as the order is persisted and `OrderCreated` is
> published. It does not wait for inventory, payment, or fulfillment.

That paragraph is the most important thing in the file. A client author who reads only the response
schema sees an order object with a `status` field and reasonably concludes that the status is the
answer. It is not; it is the *first* answer.

And a boundary statement worth copying:

> **Health and metrics** are exposed by Spring Boot Actuator [...] and are deliberately outside this
> document.

Saying where the document *stops* is part of writing a contract.

### The `/api` vs `/demo` split (ADR-002)

This is the decision that keeps the project honest, and it is the one most worth being able to defend
out loud.

**The problem.** The project's centerpiece is reproducible failure: reject this payment, pause that
consumer, republish this record. Those controls have to be reachable, because scenarios must be real
rather than animated. And the cheapest way to make a payment fail is a flag on the order request:

```
POST /api/orders {"forcePaymentFailure": true}
```

ADR-002's account of why that is fatal is precise:

> Once that exists, the production-style API is no longer production-style, and the project's central
> claim ("this is what real event-driven order processing looks like") is quietly false. A reviewer
> reading the controller would see demo scaffolding inside business logic.

Plus a second-order effect that is easy to underrate: *"a flag that exists in a DTO gets validated,
tested, documented, and eventually depended on."* Demo scaffolding does not stay contained. It
acquires tests. It becomes load-bearing.

**The decision.** Two namespaces, separated by construction and never mixed.

- **`/api`** — production-style business endpoints. No scenario parameters, no fault-injection flags,
  no demo-only fields, and **no branch anywhere in their call path that asks which scenario is
  running**.
- **`/demo`** — scenario control and fault injection. Consumer pause/resume, payment simulator
  behavior, scenario runs, environment reset.

With three concrete consequences:

1. A dedicated **Scenario Service** owns orchestration. This is the fifth service, and it exists
   entirely because of this decision.
2. Where a control must live inside the service it affects — you cannot pause a Kafka listener from
   another process — it lives under that service's `/demo` prefix, **in a separate controller**. Hence
   `DemoConsumerController` and `DemoInventoryController` sitting beside `InventoryController`.
3. Scenarios drive the system through its own public `/api` endpoints. Scenario 3 creates its order
   with the same `POST /api/orders` any client uses; only the simulator's *configured* behavior
   differs.

Point 3 is the elegant part. **Failure injection is a property of the environment, not of the
request** — which is also how real operational failures actually arrive. Nobody's production incident
begins with a client politely setting `forceFailure: true`.

**Rejected alternatives**, each for a different reason:

- **Flags on business endpoints.** Rejected outright — contradicts two scope principles and makes the
  demonstration self-undermining.
- **One all-knowing demo service that manipulates other services' databases and Kafka state
  directly.** Keeps every service's code clean. Rejected because it violates ADR-004 (Scenario
  Service would need write access to four schemas) *and because a listener cannot be paused from
  outside its own process anyway*. The `/demo` prefix inside each service is the smaller compromise:
  demo code is local, but visibly quarantined.
- **A separate Spring profile or port for demo endpoints.** Stronger isolation, and compatible with
  this decision later. Rejected as premature — and note the sharper reason: *the demo endpoints must
  be reachable in the deployed demo.* That is the whole product. Compiling them out is not what is
  wanted.

**The costs, recorded rather than hidden:**

- Scenario Service calls other services synchronously, which Phase 3 otherwise forbids. Justified as
  control plane, not workflow: no order transition depends on those calls.
- **Demo state is real state.** A run that fails halfway can leave a paused listener or an armed
  rejection behind — which is why `POST /demo/reset` exists and why it reports what it actually
  reset.
- A fifth service to run, deploy, and keep healthy, containing no business logic.
- Payment Service's rejection override is armed *before* its target order exists, so it is un-scoped
  for the duration of a run. ADR-002 calls this *"a demo-only wart, and the honest cost of not
  passing a flag through the business request."*

That last bullet is the one to remember. A known wart, named, with the tradeoff that produced it
stated. Compare it to the alternative — a `forcePaymentFailure` flag — and the wart is obviously the
better deal. But you only get to make that comparison if you wrote it down.

> **Where this pays off.** [Chapter 9](../09-production/README.md) puts the demo on the public internet. The
> `/api`–`/demo` split is what makes it possible to route the demo surface a visitor needs while
> leaving consumer-pause and payment-override endpoints cluster-internal and unreachable. A design
> decision made in Phase 0 for cleanliness turned out to be the security boundary.

---

[← The event contract](2-the-event-contract.md) · [Next: Sequencing and deferrals →](4-sequencing-and-deferrals.md)
