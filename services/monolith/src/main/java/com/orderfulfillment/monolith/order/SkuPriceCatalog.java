package com.orderfulfillment.monolith.order;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Order Service's static seeded SKU -> price map (docs/db-ownership.md, "Where prices come from").
 * Inventory Service holds stock/display_name only; no price column exists there. This is the
 * project's only product catalog and matches the four inventory seed rows.
 */
@Component
public class SkuPriceCatalog {

    private static final Map<String, BigDecimal> PRICES = Map.of(
            "SKU-001", new BigDecimal("129.00"),
            "SKU-002", new BigDecimal("189.00"),
            "SKU-003", new BigDecimal("14.50"),
            "SKU-004", new BigDecimal("249.00")
    );

    public BigDecimal priceFor(String sku) {
        return PRICES.get(sku);
    }
}
