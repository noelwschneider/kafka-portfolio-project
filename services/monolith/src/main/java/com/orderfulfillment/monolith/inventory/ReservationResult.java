package com.orderfulfillment.monolith.inventory;

import java.util.List;

/**
 * Result of {@link InventoryService#reserve}, standing in for the InventoryReserved /
 * InventoryReservationFailed event pair this phase's synchronous call replaces
 * (docs/events/event-catalog.md).
 */
public record ReservationResult(
        boolean success,
        String reservationId,
        String failureReason,
        List<Shortage> shortages
) {

    public static ReservationResult reserved(String reservationId) {
        return new ReservationResult(true, reservationId, null, List.of());
    }

    public static ReservationResult failed(String reason, List<Shortage> shortages) {
        return new ReservationResult(false, null, reason, shortages);
    }
}
