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
record StatusTransitionResult(boolean duplicate) {

    static StatusTransitionResult asDuplicate() {
        return new StatusTransitionResult(true);
    }

    static StatusTransitionResult asApplied() {
        return new StatusTransitionResult(false);
    }
}
