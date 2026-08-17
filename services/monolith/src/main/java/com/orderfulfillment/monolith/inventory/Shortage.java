package com.orderfulfillment.monolith.inventory;

/** Mirrors InventoryReservationFailed's shortages[] payload in docs/events/event-catalog.md. */
public record Shortage(String sku, int requested, int available) {
}
