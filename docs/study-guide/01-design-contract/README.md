# Chapter 1 — The design contract

**Build history:** Phase 0. Commits `ef24cb9 add planning documents` and
`22171a9 document system and contracts`.

This chapter produces no running code. That is the point of it.

Phase 0 froze seven things — service boundaries, order states, event names, the event envelope, the
core database tables, the scenario list, and the initial APIs — and wrote them down as documents that
every later phase treats as authoritative. Nothing was implemented until those documents existed.

By the end of this chapter you will have written `docs/` and nothing else, and you will understand
why that is a defensible use of the first stretch of a project rather than procrastination.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Boundaries and ownership](1-boundaries-and-ownership.md) | Why five services and not one or twelve; why they talk over Kafka and never over HTTP (ADR-001); why each owns its own schema (ADR-004) |
| 2 | [The event contract](2-the-event-contract.md) | What an event envelope is and why you need one; every field and what it is for; topics, keys, and the rule that a service publishes only to its own topic; versioning |
| 3 | [The state and API contracts](3-state-and-api-contracts.md) | The order state machine as a frozen artifact; OpenAPI as a contract rather than documentation; the `/api` vs `/demo` split (ADR-002) |
| 4 | [Sequencing, and what Phase 0 refused to decide](4-sequencing-and-deferrals.md) | Why Kubernetes waits until Phase 8 (ADR-007); what was deliberately left open, and why leaving things open is part of the discipline |

---

## Layer 1 — the problem contracts solve

You are about to build a system in which four independent processes cooperate on a workflow none of
them can see the whole of. Inventory Service will receive a JSON record from Kafka and has to know,
without asking anyone, what fields it contains, which of them it may rely on, what it is allowed to
do in response, and where to put the result.

There are only two ways for it to know that.

**One:** you write Inventory Service and Order Service together, look at one while writing the other,
and let the shape emerge. This works, right up until the moment there are four services and a
frontend, at which point the shape lives in five heads and one of them is wrong. The failure is not
dramatic — it is a field that was optional on Tuesday and required on Thursday, a status string that
one service spells `OUT_OF_STOCK` and another spells `REJECTED_OUT_OF_STOCK`, an event that two
services both believe they own.

**Two:** you decide the shape once, write it down somewhere both services point at, and treat that
document as the thing that is true. When implementation disagrees with the document, the
implementation is wrong — or the document gets changed deliberately, with a note, and everything
that depends on it gets rechecked.

The second is a **contract**. It is not documentation. Documentation describes something that
already exists; a contract constrains something that does not exist yet.

The distinction matters because it changes what happens on disagreement. If `docs/events/event-catalog.md`
is documentation and Inventory Service does something different, the doc is stale and someone should
update it eventually. If it is a contract, Inventory Service has a bug — and if it turns out the
contract was genuinely wrong, you change the contract *first*, deliberately, and then fix everything
downstream of it.

This project states that rule explicitly, in `.claude/CLAUDE.md`:

> If you're working on one service and discover a contract file is wrong or insufficient: Stop —
> don't work around it locally.

### Why this matters more here than in most projects

Two reasons specific to this build.

**It was built by parallel workstreams.** Different sessions, working at different times, on
different services, with no shared memory. A contract is the only thing that makes that possible —
it is the shared memory. Any agent picking up Inventory Service can read the event catalog and know
exactly what `OrderCreated` contains without reading Order Service at all.

**The restructuring was planned in advance.** Phase 1 is a modular monolith. Phase 2 puts Kafka in
the middle of it. Phase 3 splits it into separate deployables. The system is *deliberately* rebuilt
twice. Boundaries that exist only as package structure would be renegotiated at each step; boundaries
that exist as a frozen document survive all three shapes, because a document does not care whether
the two sides of it are in the same JVM.

---

## Layer 3 — what "frozen" actually means here

Every Phase 0 artifact carries a status line like this one, from `docs/order-state-machine.md`:

> **Status:** frozen by Phase 0. This is the authoritative order status enum and transition set for
> every service, test, and UI string.

Frozen does not mean unchangeable. It means changes go through a defined process rather than
happening incidentally:

1. Propose the change **in the contract file**, with a one-line rationale.
2. Update the affected implementations and tests.
3. Leave a note (`docs/CHANGELOG-contracts.md`) so other in-flight work knows to re-check.

The value is not the ceremony. The value is that a change becomes *visible*. A field quietly added
to a DTO is invisible; a field added to the event catalog is a diff that everything downstream can
be checked against.

> **Verify anyway.** The contracts in this repo have drifted from the implementation before, and
> this chapter found a fresh instance of it while being written — see the "Where the contract and
> the code disagree" note in [section 2](2-the-event-contract.md). Frozen is a discipline, not a
> guarantee. When something is load-bearing — an exact number, an exact behavior — check it against
> the code before you build an argument on it.

---

## Build it yourself

Phase 0's deliverable is a `docs/` directory. Create these, in this order — each one feeds the next.

1. **`docs/planning/project-overview.md`** — what you are building and, more importantly, what you
   are *not*. Pin your technology choices in a table (language, build tool, database, migration tool,
   broker image, frontend stack) so that later decisions cannot silently diverge. Write an explicit
   non-goals list; it will save you more time than anything else in the file.

2. **`docs/architecture-diagram.md`** — the service boundaries. Which services exist, what each
   owns, which arrows are allowed. Getting to "no arrow between two business services" is the whole
   exercise. See [section 1](1-boundaries-and-ownership.md).

3. **`docs/events/event-catalog.md`** — the envelope, the topic table, the key rule, and every event
   with its payload. This is the single most valuable document in the set. See
   [section 2](2-the-event-contract.md).

4. **`docs/order-state-machine.md`** — the status enum, which states are terminal, and an exhaustive
   transition table with a cause for every row. Then check two properties explicitly: every state is
   reachable, and every status-changing event has a transition. See
   [section 3](3-state-and-api-contracts.md).

5. **`docs/db-ownership.md`** — every table, its owning service, and its columns. One owner per
   table, no exceptions, no cross-schema foreign keys.

6. **`docs/openapi/*.yaml`** — one spec per service. Business endpoints under `/api`, demo endpoints
   under `/demo`, and never both in the same file for the same service without the split being
   obvious.

7. **`docs/scenarios.md`** — the failure scenarios you intend to demonstrate, each with a trigger, a
   narrative, and a concrete success condition. Write these *before* the reliability code, because
   they are what tells you which reliability code you need.

8. **`docs/adr/`** — one record per decision that had a real alternative. Context, decision,
   alternatives considered, consequences. Write the alternatives honestly, including the ones that
   were nearly right; an ADR whose rejected options are all straw men is worthless in an interview
   and worse than worthless six months later when you have forgotten why.

**Do not write any Kubernetes manifests.** See [section 4](4-sequencing-and-deferrals.md) for why
that instruction is in the plan in so many words.

---

## Next

[Section 1 — Boundaries and ownership](1-boundaries-and-ownership.md).
