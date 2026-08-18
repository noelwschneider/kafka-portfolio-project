package com.orderfulfillment.inventory;

import java.util.List;

/**
 * Result of {@link InventoryService#reserve} — which of InventoryReserved /
 * InventoryReservationFailed the caller should publish (docs/events/event-catalog.md §3), or
 * neither.
 *
 * <p>{@code duplicate} is that third outcome, and it is distinct from {@code success == false} on
 * purpose. A failed reservation is a real business answer that the order is waiting for, so it
 * publishes InventoryReservationFailed; a duplicate delivery means some earlier delivery of the
 * <em>same</em> event already produced that answer and already published it. Publishing again would
 * be exactly the duplicate side effect Scenario 4 exists to prove does not happen.
 */
public record ReservationResult(
        boolean success,
        String reservationId,
        String failureReason,
        List<Shortage> shortages,
        boolean duplicate
) {

    /**
     * The event had already been processed by this consumer; no reservation was attempted and
     * nothing must be published.
     */
    public static final ReservationResult DUPLICATE = new ReservationResult(false, null, null, List.of(), true);

    public static ReservationResult reserved(String reservationId) {
        return new ReservationResult(true, reservationId, null, List.of(), false);
    }

    public static ReservationResult failed(String reason, List<Shortage> shortages) {
        return new ReservationResult(false, null, reason, shortages, false);
    }
}
