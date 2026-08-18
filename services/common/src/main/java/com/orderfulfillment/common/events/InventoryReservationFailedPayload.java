package com.orderfulfillment.common.events;

import java.util.List;

/** docs/events/event-catalog.md §3 — InventoryReservationFailed. Published by Inventory Service on {@code inventory.events}. */
public record InventoryReservationFailedPayload(String orderId, String reason, List<ShortageItem> shortages) {
}
