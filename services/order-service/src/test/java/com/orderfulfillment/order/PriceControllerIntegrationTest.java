package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderfulfillment.order.dto.SkuPrice;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * {@code GET /api/prices} (issue #32) — read-only exposure of {@link SkuPriceCatalog}, the same
 * seeded map {@code OrderService.createOrder} already uses to price a line. Proves the endpoint
 * returns all four seeded SKUs with the exact prices `docs/db-ownership.md`'s "Where prices come
 * from" table freezes, sorted by SKU.
 */
class PriceControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void listsAllSeededPricesSortedBySku() {
        List<SkuPrice> prices = client.get().uri("/api/prices")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<List<SkuPrice>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(prices).containsExactly(
                new SkuPrice("SKU-001", new BigDecimal("129.00")),
                new SkuPrice("SKU-002", new BigDecimal("189.00")),
                new SkuPrice("SKU-003", new BigDecimal("14.50")),
                new SkuPrice("SKU-004", new BigDecimal("249.00")));
    }
}
