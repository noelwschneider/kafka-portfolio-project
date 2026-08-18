package com.orderfulfillment.inventory;

import java.util.List;

/** Result of {@link InventoryService#release} — the lines actually released and the shared
 * reservation-group id they belonged to, so a caller can publish InventoryReleased
 * (docs/events/event-catalog.md §3) with real data rather than reconstructing it from scratch. */
public record ReleaseResult(String reservationId, List<OrderLine> items) {

    public static final ReleaseResult NONE = new ReleaseResult(null, List.of());
}
