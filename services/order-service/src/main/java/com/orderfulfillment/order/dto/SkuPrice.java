package com.orderfulfillment.order.dto;

import java.math.BigDecimal;

/** One row of {@code GET /api/prices} — docs/openapi/order-service.yaml's {@code SkuPrice} schema. */
public record SkuPrice(String sku, BigDecimal unitPrice) {
}
