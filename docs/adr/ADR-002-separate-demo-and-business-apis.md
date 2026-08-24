# ADR-002: Keep demo/fault-injection APIs separate from business APIs

- **Status:** Accepted. Enforced from Phase 0's contracts onward.
- **Date:** 2026-08-17 (Phase 0)

## Context

The project's centerpiece is a set of reproducible failure scenarios: reject this payment, pause that
consumer, republish this event, publish an unprocessable record. Those controls have to exist
somewhere reachable, because `docs/planning/engineering-rules.md` rule 10 requires scenario behavior to
be real rather than a frontend animation.

That creates an obvious temptation with an obvious cost. The cheapest way to make a payment fail is a
flag on the order request — `POST /api/orders {"forcePaymentFailure": true}`. Once that exists, the
production-style API is no longer production-style, and the project's central claim ("this is what
real event-driven order processing looks like") is quietly false. A reviewer reading the controller
would see demo scaffolding inside business logic.

`docs/planning/project-overview.md`'s Scope Principles name both sides of this: "keep
production-style APIs separate from demo/fault-injection APIs" and "do not put scenario-specific
hacks inside normal business endpoints."

## Decision

Two namespaces, separated by construction and never mixed:

- **`/api`** — production-style business endpoints. No scenario parameters, no fault-injection flags,
  no demo-only fields, no branch anywhere in their call path that asks which scenario is running.
- **`/demo`** — scenario control and fault injection. Consumer pause/resume, payment simulator
  behavior, scenario runs, environment reset.

Concretely:

- A dedicated **Scenario Service** owns scenario orchestration
  (`docs/openapi/scenario-service.yaml`), the long-term direction recommended by
  `docs/planning/sprint-1/backend-design.md` 4.6.
- Where a control must live inside the service it affects — pausing a listener, configuring the
  payment simulator — it lives under that service's `/demo` prefix, in a separate controller from
  anything under `/api` (`docs/openapi/inventory-service.yaml`,
  `docs/openapi/payment-service.yaml`, `docs/openapi/fulfillment-service.yaml`).
- Scenarios drive the system through its **own public `/api` endpoints**. Scenario 3 creates its
  order with the same `POST /api/orders` any client uses; only the simulator's configured behavior
  differs.
- `/demo` endpoints can be disabled or protected by a simple demo configuration
  (`docs/planning/sprint-1/high-level-design.md`'s Security Scope section) without touching `/api`.

## Alternatives considered

**Scenario flags on business endpoints.** Fewest moving parts, no extra service, no cross-service
control calls. Rejected outright: it contradicts two explicit scope principles, and it makes the
demonstration self-undermining, since the "real" API would visibly contain demo behavior. It also
leaks: a flag that exists in a DTO gets validated, tested, documented, and eventually depended on.

**A single all-knowing demo service that manipulates other services' databases or Kafka state
directly.** Keeps every service's own code free of demo concerns. Rejected because it violates the
database ownership boundary in ADR-004 — Scenario Service would need write access to four schemas —
and because a listener cannot be paused from outside its own process anyway. The `/demo` prefix
inside each service is the smaller compromise: the demo code is local to the service, but visibly
quarantined.

**Separate build profile or port for demo endpoints** (Spring profile, or a second servlet
container). Stronger isolation than a URL prefix, and compatible with this decision later. Rejected
for v1 as premature: a path prefix plus separate controllers is enough to keep the boundary legible,
and the demo endpoints must be *reachable* in the deployed demo — that is the whole point of the
product — so compiling them out is not actually what is wanted.

## Consequences and tradeoffs

**Accepted costs.**

- Scenario Service calls other services synchronously over HTTP, which
  `docs/planning/sprint-1/implementation-phases.md`'s Phase 3 otherwise advises against. Justified as control
  plane rather than workflow: no order transition depends on those calls, and a scenario has to be
  able to report a deterministic start. Documented at the top of
  `docs/openapi/scenario-service.yaml`.
- Demo state is real state. A run that fails halfway can leave a paused listener or an armed
  rejection behind, which is why `POST /demo/reset` exists and why it reports what it actually reset.
- A fifth service to run, deploy, and keep healthy — one that contains no business logic.
- Payment Service's rejection override is armed before its target order exists, so it is un-scoped
  for the duration of a run (`docs/scenarios.md`, Scenario 3). A demo-only wart, and the honest cost
  of not passing a flag through the business request.

**What it buys.**

- Reviewers can read `docs/openapi/order-service.yaml` and see an ordinary order API, because that is
  what it is.
- Failure injection is a property of the environment, not of the request — which is how real
  operational failures actually arrive.
- The demo surface can be locked down in one place if this is ever deployed publicly.
