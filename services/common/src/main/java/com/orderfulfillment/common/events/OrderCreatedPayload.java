package com.orderfulfillment.common.events;

import java.util.List;

/** docs/events/event-catalog.md §3 — OrderCreated. Published by Order Service on {@code orders.events}. */
public record OrderCreatedPayload(String orderId, String customerId, List<EventItem> items) {
}
