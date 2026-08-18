package com.orderfulfillment.inventory;

import java.util.List;

/**
 * Result of {@link InventoryService#release} — the lines actually released and the shared
 * reservation-group id they belonged to, so a caller can publish InventoryReleased
 * (docs/events/event-catalog.md §3) with real data rather than reconstructing it from scratch.
 *
 * <p>{@link #NONE} and {@link #DUPLICATE} both mean "publish nothing", but they are different
 * facts and are kept apart so the logs do not lie: NONE means this order had no live reservation
 * to release, DUPLICATE means this PaymentRejected event had already been released by an earlier
 * delivery.
 */
public record ReleaseResult(String reservationId, List<OrderLine> items, boolean duplicate) {

    public static final ReleaseResult NONE = new ReleaseResult(null, List.of(), false);

    public static final ReleaseResult DUPLICATE = new ReleaseResult(null, List.of(), true);

    public ReleaseResult(String reservationId, List<OrderLine> items) {
        this(reservationId, items, false);
    }
}
