package com.orderfulfillment.common.events;

import java.time.Instant;
import java.util.List;

/** docs/events/event-catalog.md §3 — InventoryReserved. Published by Inventory Service on {@code inventory.events}. */
public record InventoryReservedPayload(String orderId, String reservationId, List<EventItem> items, Instant reservedAt) {
}
