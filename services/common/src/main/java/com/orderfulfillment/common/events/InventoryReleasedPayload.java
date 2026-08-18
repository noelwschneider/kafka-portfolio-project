package com.orderfulfillment.common.events;

import java.time.Instant;
import java.util.List;

/** docs/events/event-catalog.md §3 — InventoryReleased. Published by Inventory Service on {@code inventory.events}.
 * No consumer in v1 (see the catalog's judgment-call note); published so the compensation step is
 * observable in Kafka rather than a silent DB-only side effect. */
public record InventoryReleasedPayload(String orderId, String reservationId, List<EventItem> items, String reason, Instant releasedAt) {
}
