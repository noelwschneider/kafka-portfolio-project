package com.orderfulfillment.monolith;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderfulfillment.monolith.inventory.dto.InventoryItemDto;
import com.orderfulfillment.monolith.order.dto.CreateOrderItem;
import com.orderfulfillment.monolith.order.dto.CreateOrderRequest;
import com.orderfulfillment.monolith.order.dto.OrderAccepted;
import com.orderfulfillment.monolith.order.dto.OrderDetail;
import com.orderfulfillment.monolith.payment.dto.PaymentAttemptDto;
import com.orderfulfillment.monolith.payment.dto.PaymentBehaviorDto;
import com.orderfulfillment.monolith.payment.dto.PaymentBehaviorMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Phase 1 exit criterion: "An order that clears inventory but fails payment reaches
 * PAYMENT_FAILED, and its inventory reservation is released (verify the stock count actually
 * returns to its pre-reservation value)" (docs/planning/implementation-phases.md).
 */
class PaymentRejectionIntegrationTest extends AbstractIntegrationTest {

    @Test
    void orderWithRejectedPaymentReachesPaymentFailedAndReleasesReservation() {
        client.put().uri("/demo/payment-behavior")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaymentBehaviorDto(PaymentBehaviorMode.REJECT, null, "CARD_DECLINED"))
                .exchange()
                .expectStatus().isOk();

        InventoryItemDto before = client.get().uri("/api/inventory/SKU-002").exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();

        CreateOrderRequest request = new CreateOrderRequest("demo-customer",
                List.of(new CreateOrderItem("SKU-002", 1)));
        OrderAccepted accepted = client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderAccepted.class)
                .returnResult().getResponseBody();

        assertThat(accepted.status()).isEqualTo("PAYMENT_FAILED");

        OrderDetail detail = client.get().uri("/api/orders/" + accepted.id()).exchange()
                .expectBody(OrderDetail.class).returnResult().getResponseBody();
        assertThat(detail.status()).isEqualTo("PAYMENT_FAILED");
        assertThat(detail.statusHistory()).extracting("status")
                .containsExactly("PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING", "PAYMENT_FAILED");

        PaymentAttemptDto payment = client.get().uri("/api/payments/" + accepted.id()).exchange()
                .expectBody(PaymentAttemptDto.class).returnResult().getResponseBody();
        assertThat(payment.status()).isEqualTo("REJECTED");
        assertThat(payment.failureReason()).isEqualTo("CARD_DECLINED");

        client.get().uri("/api/shipments/" + accepted.id()).exchange().expectStatus().isNotFound();

        InventoryItemDto after = client.get().uri("/api/inventory/SKU-002").exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();
        assertThat(after.reservedQuantity())
                .as("reservation must be released back to its pre-order value, not just marked FAILED on the order")
                .isEqualTo(before.reservedQuantity());
    }
}
