package com.orderfulfillment.order;

/**
 * Result of one of {@link OrderPersistence}'s transactional status-transition methods — mirrors
 * Inventory Service's {@code ReservationResult}/{@code ReleaseResult} shape
 * (docs/reliability-pattern.md §8 point 5).
 *
 * <p>{@code duplicate} is a distinct outcome from any business result on purpose: it means some
 * earlier (or concurrent) delivery of the same event already won the {@code processed_events}
 * claim and already applied the transition (and, where applicable, already recorded whatever
 * outbound event follows it). A caller that saw {@code duplicate() == true} must not produce any
 * further side effect — that is the downstream half of the duplicate side effect Scenario 4 rules
 * out.
 *
 * <p>Phase 6 removed this record's {@code totalAmount} field. It existed so
 * {@link OrderPersistence#appendInventoryReservedTransition}'s caller could build the
 * {@code PaymentRequested} payload after the transaction committed; that event is now recorded in
 * the outbox inside the transaction itself (ADR-006), so nothing needs to escape it any more.
 */
record StatusTransitionResult(Outcome outcome) {

    /**
     * ADR-009 added {@link #DEFERRED} and {@link #STALE} alongside the original applied/duplicate
     * pair, because "the transition was consumed but not written" now has two honest reasons that
     * are not duplicates: its predecessor has not arrived yet, or the order has already moved past
     * it. Reporting either as {@code applied} would be a lie about what the database holds.
     */
    enum Outcome {
        /** The transition was written: one {@code order_status_history} row, {@code orders.status} moved. */
        APPLIED,
        /** Some earlier or concurrent delivery of the same event already won the ledger claim. */
        DUPLICATE,
        /** Parked in {@code deferred_transitions} until its predecessor transition is applied. */
        DEFERRED,
        /** Dropped: the order has already passed this point, or has reached a terminal state. */
        STALE
    }

    boolean duplicate() {
        return outcome == Outcome.DUPLICATE;
    }

    static StatusTransitionResult asDuplicate() {
        return new StatusTransitionResult(Outcome.DUPLICATE);
    }

    static StatusTransitionResult asApplied() {
        return new StatusTransitionResult(Outcome.APPLIED);
    }

    static StatusTransitionResult asDeferred() {
        return new StatusTransitionResult(Outcome.DEFERRED);
    }

    static StatusTransitionResult asStale() {
        return new StatusTransitionResult(Outcome.STALE);
    }
}
