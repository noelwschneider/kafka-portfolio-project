package com.orderfulfillment.order;

import com.orderfulfillment.order.dto.SkuPrice;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/openapi/order-service.yaml's {@code GET /api/prices} (issue #32). Read-only exposure of the
 * seeded SKU price map {@link SkuPriceCatalog} already uses at order-creation time, so a demo user
 * can see a price before adding an item to an order. No write path and no price validation at
 * checkout — this is display only, and Order Service remains the sole owner of price data
 * (docs/db-ownership.md, "Where prices come from").
 */
@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final SkuPriceCatalog priceCatalog;

    public PriceController(SkuPriceCatalog priceCatalog) {
        this.priceCatalog = priceCatalog;
    }

    @GetMapping
    public List<SkuPrice> listPrices() {
        return priceCatalog.allPrices().stream()
                .map(entry -> new SkuPrice(entry.getKey(), entry.getValue()))
                .toList();
    }
}
