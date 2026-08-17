package com.orderfulfillment.monolith;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderfulfillment.monolith.fulfillment.dto.ShipmentDto;
import com.orderfulfillment.monolith.inventory.dto.InventoryItemDto;
import com.orderfulfillment.monolith.order.dto.CreateOrderItem;
import com.orderfulfillment.monolith.order.dto.CreateOrderRequest;
import com.orderfulfillment.monolith.order.dto.OrderAccepted;
import com.orderfulfillment.monolith.order.dto.OrderDetail;
import com.orderfulfillment.monolith.payment.dto.PaymentAttemptDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Phase 1 exit criterion: "Happy-path order (adequate stock, payment succeeds) reaches
 * FULFILLED" (docs/planning/implementation-phases.md).
 */
class HappyPathIntegrationTest extends AbstractIntegrationTest {

    @Test
    void orderWithAdequateStockAndSuccessfulPaymentReachesFulfilled() {
        InventoryItemDto before = client.get().uri("/api/inventory/SKU-001").exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();

        CreateOrderRequest request = new CreateOrderRequest("demo-customer",
                List.of(new CreateOrderItem("SKU-001", 1)));
        OrderAccepted accepted = client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderAccepted.class)
                .returnResult().getResponseBody();

        assertThat(accepted).isNotNull();
        assertThat(accepted.status()).isEqualTo("FULFILLED");

        OrderDetail detail = client.get().uri("/api/orders/" + accepted.id()).exchange()
                .expectBody(OrderDetail.class).returnResult().getResponseBody();
        assertThat(detail.status()).isEqualTo("FULFILLED");
        assertThat(detail.statusHistory()).extracting("status")
                .containsExactly("PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING", "PAID", "FULFILLMENT_PENDING", "FULFILLED");

        PaymentAttemptDto payment = client.get().uri("/api/payments/" + accepted.id()).exchange()
                .expectBody(PaymentAttemptDto.class).returnResult().getResponseBody();
        assertThat(payment.status()).isEqualTo("AUTHORIZED");

        ShipmentDto shipment = client.get().uri("/api/shipments/" + accepted.id()).exchange()
                .expectBody(ShipmentDto.class).returnResult().getResponseBody();
        assertThat(shipment.status()).isEqualTo("CREATED");

        InventoryItemDto after = client.get().uri("/api/inventory/SKU-001").exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();
        assertThat(after.reservedQuantity()).isEqualTo(before.reservedQuantity() + 1);
    }
}
