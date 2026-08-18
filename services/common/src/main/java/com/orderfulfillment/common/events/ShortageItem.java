package com.orderfulfillment.common.events;

/** Mirrors InventoryReservationFailed.shortages[] in docs/events/event-catalog.md §3. */
public record ShortageItem(String sku, int requested, int available) {
}
