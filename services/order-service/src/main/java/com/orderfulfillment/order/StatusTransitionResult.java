package com.orderfulfillment.order;

import java.math.BigDecimal;

/**
 * Result of one of {@link OrderPersistence}'s transactional status-transition methods — mirrors
 * Inventory Service's {@code ReservationResult}/{@code ReleaseResult} shape
 * (docs/reliability-pattern.md §8 point 5).
 *
 * <p>{@code duplicate} is a distinct outcome from any business result on purpose: it means some
 * earlier (or concurrent) delivery of the same event already won the {@code processed_events}
 * claim and already applied the transition (and, where applicable, already published whatever
 * outbound event follows it). A caller that saw {@code duplicate() == true} must not publish
 * anything — that is the downstream half of the duplicate side effect Scenario 4 rules out.
 *
 * <p>{@code totalAmount} is populated only by {@link OrderPersistence#appendInventoryReservedTransition},
 * which is the one transition whose caller needs data back out of the transaction (to build the
 * {@code PaymentRequested} payload) rather than just a duplicate/applied signal.
 */
record StatusTransitionResult(boolean duplicate, BigDecimal totalAmount) {

    static StatusTransitionResult asDuplicate() {
        return new StatusTransitionResult(true, null);
    }

    static StatusTransitionResult asApplied() {
        return new StatusTransitionResult(false, null);
    }

    static StatusTransitionResult asApplied(BigDecimal totalAmount) {
        return new StatusTransitionResult(false, totalAmount);
    }
}
