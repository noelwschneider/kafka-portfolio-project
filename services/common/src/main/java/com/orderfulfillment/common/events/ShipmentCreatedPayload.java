package com.orderfulfillment.common.events;

import java.time.Instant;

/** docs/events/event-catalog.md §3 — ShipmentCreated. Published by Fulfillment Service on {@code fulfillment.events}. */
public record ShipmentCreatedPayload(String orderId, String shipmentId, String trackingNumber, Instant createdAt) {
}
