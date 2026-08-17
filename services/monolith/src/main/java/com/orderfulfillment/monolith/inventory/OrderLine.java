package com.orderfulfillment.monolith.inventory;

/** A requested (sku, quantity) pair — the shape shared by OrderCreated's items[] payload. */
public record OrderLine(String sku, int quantity) {
}
