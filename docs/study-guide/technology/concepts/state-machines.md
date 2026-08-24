# Finite state machines

*Referenced from [Chapter 1.3 — The state and API contracts](../../01-design-contract/3-state-and-api-contracts.md).*

---

## The formalism

Small and old. Four parts:

1. A finite set of **states**.
2. A designated **initial** state.
3. A set of **terminal** states — states with no exit.
4. A set of legal **transitions**: `(from, to)` pairs, each with a named cause.

The power is entirely in what it **forbids**. If the transition set is exhaustive, any `(from, to)`
not in it is invalid *by definition* — and "invalid" becomes something code can detect rather than
something a reviewer might notice.

## Why bother, for something as simple as an order status

Without an explicit transition set, "what states can this be in, and how did it get here" has no
answer except reading every writer. Any writer can put the entity into any state. Three specific
failures follow:

**Vocabulary drift.** Two documents name the same state differently, two components implement both, a
test asserts one and the UI displays the other. Nobody notices until something fails for a reason
unrelated to what actually broke.

**Silent invalid states.** An entity reaches a combination nobody intended, and there is no place in
the code where that could have been caught.

**Undoing terminal outcomes.** The one that hurts most in an asynchronous system: a late message
overwrites a finished state. Without a rule that nothing leaves a terminal state, a delayed event can
move a completed order back to in-progress — and it is not even wrong locally, because the code that
wrote it had no way to know.

## Making it real

The table is worthless if nothing consults it. A transition table that exists only as prose and
documentation comments is a description of what the code *ought* to do, and the gap between that and
what it does is invisible until something breaks.

The minimum useful form is a map from target state to the states it may be entered from:

```java
VALID_PREDECESSORS.put(PAYMENT_PENDING, Set.of(INVENTORY_RESERVED));
VALID_PREDECESSORS.put(PAID,            Set.of(PAYMENT_PENDING));
VALID_PREDECESSORS.put(FULFILLED,       Set.of(FULFILLMENT_PENDING));
```

Then every write consults it, and a transition whose current state is not in the target's set is never
durably applied.

Terminality is worth putting on the enum itself, so it travels with the value:

```java
private static final Set<OrderStatus> TERMINAL =
        Set.of(REJECTED_OUT_OF_STOCK, PAYMENT_FAILED, FULFILLED, FAILED);

public boolean isTerminal() { return TERMINAL.contains(this); }
```

## Two checks worth doing on the table itself

Mechanical, quick, and they catch real errors:

**Every state is reachable.** Map each state to the transition that produces it. This catches the
classic case of an enum value nothing can actually get into.

**Every cause is accounted for.** Map each event (or command, or input) to its transition. Causes with
*no* state effect should be listed explicitly, with the reason — so "missing from the table" cannot be
mistaken for an oversight.

## Distinguishing kinds of transition

Two categories are worth marking separately, because they answer different questions for a reader:

- **Externally caused** — something arrived and we reacted. There is an event, a request, a message.
- **Internal** — the owner moved the entity itself, with no inbound cause.

Marking the internal ones saves the next reader from hunting for an event that does not exist. In an
event-driven system it is also the honest way to record a transition whose "cause" is an *outbound*
message rather than an inbound one.

## Rejecting vs. deferring

When an invalid transition arrives, there are two defensible responses and they are not
interchangeable:

- **Reject it.** The transition is wrong and always will be. Drop it, log it, move on.
- **Defer it.** The transition is *premature* — legal, but its predecessor has not happened yet.
  Store it and apply it once the predecessor does.

Distinguishing the two requires knowing whether the arriving transition is *earlier* or *later* than
the current state along the expected path, which is more than the predecessor table alone can tell
you. A monotonic ordering over the happy path is the usual addition.

In a system with a single writer this distinction rarely comes up. In one where several independent
sources write the same state — several message consumers, say, with no ordering guarantee between them
— it is essential, and it is the subject of [Chapter 4](../../04-reliability/README.md).
