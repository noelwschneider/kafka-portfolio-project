package com.orderfulfillment.order;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The frozen transition table of docs/order-state-machine.md §3, as code.
 *
 * <p>Before ADR-009 this table existed only as prose: {@link OrderPersistence} wrote whatever status
 * its caller asked for, with no reference to the order's current status at all. That is what let
 * {@code ShipmentCreated} — raced ahead of {@code PaymentAuthorized} through the deliberate
 * {@code payments.events} fan-out (docs/events/event-catalog.md §3) — write {@code FULFILLED}
 * straight out of {@code PAYMENT_PENDING}, and then let the late {@code PaymentAuthorized} overwrite
 * that terminal state back to {@code FULFILLMENT_PENDING}
 * (docs/agent-reports/phase-10-scaling-demo.md §4).
 *
 * <p>Two things are encoded here:
 *
 * <ul>
 *   <li>{@link #VALID_PREDECESSORS} — the authoritative "from" set for each target status, read
 *       directly off §3's table. A transition whose current status is not in the target's set is
 *       never durably written.
 *   <li>{@link #PROGRESS} — a monotonic ordinal along the happy path, used only to tell an
 *       <em>earlier</em> transition arriving late (which must never undo later progress) from a
 *       <em>later</em> transition arriving early (which is legitimate and merely premature). It is
 *       not part of the frozen contract; it is a derived ordering over §3's happy-path chain.
 * </ul>
 */
final class OrderTransitions {

    /** docs/order-state-machine.md §3 — for each target status, the states it may be entered from. */
    private static final Map<OrderStatus, Set<OrderStatus>> VALID_PREDECESSORS;

    /**
     * Position along the happy-path chain PENDING → … → FULFILLED. Absent for the three failure
     * outcomes, which branch off that chain rather than sitting on it.
     */
    private static final Map<OrderStatus, Integer> PROGRESS;

    static {
        VALID_PREDECESSORS = new EnumMap<>(OrderStatus.class);
        // 1 — created by POST /api/orders; no predecessor status exists.
        VALID_PREDECESSORS.put(OrderStatus.PENDING, Set.of());
        // 2, 3
        VALID_PREDECESSORS.put(OrderStatus.INVENTORY_RESERVED, Set.of(OrderStatus.PENDING));
        VALID_PREDECESSORS.put(OrderStatus.REJECTED_OUT_OF_STOCK, Set.of(OrderStatus.PENDING));
        // 4
        VALID_PREDECESSORS.put(OrderStatus.PAYMENT_PENDING, Set.of(OrderStatus.INVENTORY_RESERVED));
        // 5, 6
        VALID_PREDECESSORS.put(OrderStatus.PAID, Set.of(OrderStatus.PAYMENT_PENDING));
        VALID_PREDECESSORS.put(OrderStatus.PAYMENT_FAILED, Set.of(OrderStatus.PAYMENT_PENDING));
        // 7
        VALID_PREDECESSORS.put(OrderStatus.FULFILLMENT_PENDING, Set.of(OrderStatus.PAID));
        // 8
        VALID_PREDECESSORS.put(OrderStatus.FULFILLED, Set.of(OrderStatus.FULFILLMENT_PENDING));
        // 9 — "any non-terminal".
        VALID_PREDECESSORS.put(OrderStatus.FAILED, Set.of(
                OrderStatus.PENDING, OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_PENDING,
                OrderStatus.PAID, OrderStatus.FULFILLMENT_PENDING));

        PROGRESS = new EnumMap<>(OrderStatus.class);
        PROGRESS.put(OrderStatus.PENDING, 1);
        PROGRESS.put(OrderStatus.INVENTORY_RESERVED, 2);
        PROGRESS.put(OrderStatus.PAYMENT_PENDING, 3);
        PROGRESS.put(OrderStatus.PAID, 4);
        PROGRESS.put(OrderStatus.FULFILLMENT_PENDING, 5);
        PROGRESS.put(OrderStatus.FULFILLED, 6);
    }

    private OrderTransitions() {
    }

    /** What {@link OrderPersistence} should do with a requested transition, given where the order is. */
    enum Verdict {
        /** The transition is valid from the order's current status: write it. */
        APPLY,
        /**
         * The transition belongs to a point the order has already passed (or has already left the
         * chain entirely, by reaching a terminal state). Applying it would move the order backwards
         * and, in the terminal case, revert a final answer. Drop it.
         */
        STALE,
        /**
         * The transition is a legitimate <em>future</em> step whose predecessor has not been applied
         * yet — the out-of-order arrival ADR-009 exists for. Park it and re-offer it once the
         * missing predecessor lands.
         */
        AHEAD
    }

    static Verdict classify(OrderStatus current, OrderStatus target) {
        if (VALID_PREDECESSORS.getOrDefault(target, Set.of()).contains(current)) {
            return Verdict.APPLY;
        }
        // Already there: a redelivery that got past the ledger, or a deferred row drained twice.
        // Either way there is nothing to write and nothing to undo.
        if (current == target) {
            return Verdict.STALE;
        }
        // Nothing leaves a terminal state — docs/order-state-machine.md §3. This is the half of the
        // guard that stops a late PaymentAuthorized from reverting FULFILLED.
        if (current.isTerminal()) {
            return Verdict.STALE;
        }
        Integer currentProgress = PROGRESS.get(current);
        Integer targetProgress = PROGRESS.get(target);
        if (currentProgress != null && targetProgress != null && targetProgress < currentProgress) {
            return Verdict.STALE;
        }
        return Verdict.AHEAD;
    }
}
