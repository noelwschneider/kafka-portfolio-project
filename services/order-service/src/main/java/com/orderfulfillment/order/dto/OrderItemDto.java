package com.orderfulfillment.order.dto;

import java.math.BigDecimal;

public record OrderItemDto(String sku, int quantity, BigDecimal unitPrice) {
}
