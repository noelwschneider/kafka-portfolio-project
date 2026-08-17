package com.orderfulfillment.monolith;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderfulfillment.monolith.inventory.dto.InventoryItemDto;
import com.orderfulfillment.monolith.order.dto.CreateOrderItem;
import com.orderfulfillment.monolith.order.dto.CreateOrderRequest;
import com.orderfulfillment.monolith.order.dto.OrderAccepted;
import com.orderfulfillment.monolith.order.dto.OrderDetail;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Phase 1 exit criterion: "An order requesting more than available stock reaches
 * REJECTED_OUT_OF_STOCK and no payment/shipment side effects occur"
 * (docs/planning/implementation-phases.md).
 */
class OutOfStockIntegrationTest extends AbstractIntegrationTest {

    @Test
    void orderExceedingAvailableStockIsRejectedWithNoSideEffects() {
        InventoryItemDto before = client.get().uri("/api/inventory/SKU-004").exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();
        int requested = before.availableQuantity() - before.reservedQuantity() + 1;

        CreateOrderRequest request = new CreateOrderRequest("demo-customer",
                List.of(new CreateOrderItem("SKU-004", requested)));
        OrderAccepted accepted = client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderAccepted.class)
                .returnResult().getResponseBody();

        assertThat(accepted.status()).isEqualTo("REJECTED_OUT_OF_STOCK");

        OrderDetail detail = client.get().uri("/api/orders/" + accepted.id()).exchange()
                .expectBody(OrderDetail.class).returnResult().getResponseBody();
        assertThat(detail.status()).isEqualTo("REJECTED_OUT_OF_STOCK");
        assertThat(detail.statusHistory()).extracting("status")
                .containsExactly("PENDING", "REJECTED_OUT_OF_STOCK");

        client.get().uri("/api/payments/" + accepted.id()).exchange().expectStatus().isNotFound();
        client.get().uri("/api/shipments/" + accepted.id()).exchange().expectStatus().isNotFound();

        InventoryItemDto after = client.get().uri("/api/inventory/SKU-004").exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();
        assertThat(after.reservedQuantity()).isEqualTo(before.reservedQuantity());
        assertThat(after.availableQuantity()).isEqualTo(before.availableQuantity());
    }
}
