package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.inventory.dto.InventoryItemDto;
import com.orderfulfillment.inventory.dto.RestoreInventoryRequest;
import com.orderfulfillment.inventory.dto.UpdateInventoryRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * docs/openapi/inventory-service.yaml's {@code POST /demo/inventory/{sku}/restore} — the endpoint
 * added to fix the reset defect in
 * docs/agent-reports/sprint-2/deployment-execution-report.md §6: reservations are never released on
 * the successful-fulfillment path, so a long-running demo's {@code reservedQuantity} routinely
 * exceeds the seed value by the time a reset runs, and the production {@code PUT /api/inventory/{sku}}
 * cannot restore that state because it rejects any {@code availableQuantity} below the current
 * {@code reservedQuantity}.
 */
class DemoInventoryRestoreIntegrationTest extends AbstractIntegrationTest {

    /** Real seed for SKU-004, per DemoResetService's SEED_QUANTITIES / docs/db-ownership.md. */
    private static final int SKU_004_SEED = 2;

    @Test
    void restoreClearsReservationsEvenWhenTheyExceedTheSeedValue() {
        // Give SKU-004 enough available stock to reserve past its seed of 2 — simulating the
        // wedged state a long-running demo reaches: reservedQuantity that has drifted above the
        // seed because reservations are never released on successful fulfillment.
        client.put().uri("/api/inventory/SKU-004")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateInventoryRequest(10))
                .exchange().expectStatus().isOk();

        for (int i = 0; i < 3; i++) {
            String orderId = "order-restore-test-" + UUID.randomUUID();
            publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                    new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-004", 1))));
        }

        InventoryItemDto reserved = awaitReservedAtLeast("SKU-004", 3);
        assertThat(reserved.reservedQuantity()).isGreaterThanOrEqualTo(3);
        assertThat(reserved.reservedQuantity()).isGreaterThan(SKU_004_SEED);

        // The production PUT structurally cannot fix this: restoring the real seed (2) would be
        // below the current reservedQuantity (>=3), so it is rejected with 409.
        client.put().uri("/api/inventory/SKU-004")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateInventoryRequest(SKU_004_SEED))
                .exchange().expectStatus().isEqualTo(409);

        // The demo restore endpoint sets both fields atomically and succeeds regardless.
        InventoryItemDto restored = client.post().uri("/demo/inventory/SKU-004/restore")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RestoreInventoryRequest(SKU_004_SEED))
                .exchange().expectStatus().isOk()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();

        assertThat(restored.availableQuantity()).isEqualTo(SKU_004_SEED);
        assertThat(restored.reservedQuantity()).isEqualTo(0);
        assertThat(restored.availableQuantity() - restored.reservedQuantity()).isEqualTo(SKU_004_SEED);

        InventoryItemDto afterGet = getInventory("SKU-004");
        assertThat(afterGet.availableQuantity()).isEqualTo(SKU_004_SEED);
        assertThat(afterGet.reservedQuantity()).isEqualTo(0);
    }

    private InventoryItemDto awaitReservedAtLeast(String sku, int minReserved) {
        java.util.concurrent.atomic.AtomicReference<InventoryItemDto> latest = new java.util.concurrent.atomic.AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            InventoryItemDto current = getInventory(sku);
            latest.set(current);
            assertThat(current.reservedQuantity()).isGreaterThanOrEqualTo(minReserved);
        });
        return latest.get();
    }

    private InventoryItemDto getInventory(String sku) {
        return client.get().uri("/api/inventory/" + sku).exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();
    }

    private void publish(String topic, String eventType, String aggregateId, Object payload) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(),
                () -> eventPublisher.publish(topic, eventType, aggregateId, payload));
    }
}
