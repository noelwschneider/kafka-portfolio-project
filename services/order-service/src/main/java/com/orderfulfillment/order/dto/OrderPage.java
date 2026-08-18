package com.orderfulfillment.order.dto;

import java.util.List;

public record OrderPage(
        List<OrderSummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
