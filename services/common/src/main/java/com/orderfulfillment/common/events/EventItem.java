package com.orderfulfillment.common.events;

/** The {@code {sku, quantity}} shape shared by OrderCreated.items[], InventoryReserved.items[],
 * and InventoryReleased.items[] in docs/events/event-catalog.md §3. */
public record EventItem(String sku, int quantity) {
}
