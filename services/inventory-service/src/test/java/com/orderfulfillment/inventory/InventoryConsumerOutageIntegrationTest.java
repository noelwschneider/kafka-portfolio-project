package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.kafka.ConsumerState;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.inventory.dto.InventoryItemDto;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Scenario 5 — Consumer Outage and Recovery (docs/scenarios.md), Inventory Service's half.
 *
 * <p>The outage is produced through the service's real {@code /demo/consumers/{name}/pause} HTTP
 * endpoint — the same call Scenario Service will make — rather than by reaching into
 * {@code KafkaListenerEndpointRegistry} from the test. That matters: the endpoints were frozen in
 * Phase 0 and unimplemented until Phase 4, so a test that bypassed them would leave the thing being
 * shipped untested and would not notice, say, a pause that returns before it has taken effect.
 *
 * <p>Nothing here is a simulated delay. While paused, the record sits durably on
 * {@code orders.events} at an uncommitted offset; on resume the same consumer picks it up from
 * where it left off.
 */
class InventoryConsumerOutageIntegrationTest extends AbstractIntegrationTest {

    private static final String SKU = "SKU-003"; // seeded at 100 — plenty, and not contended
    private static final String LISTENER = "order-created";

    @Autowired
    private InventoryReservationRepository reservationRepository;

    private String orderId;

    @AfterEach
    void resumeAndRestore() {
        // Resume unconditionally: a failure between pause and resume would otherwise leave the
        // listener paused and break every test that runs after this one.
        resume();
        if (orderId != null) {
            jdbcClient.sql("DELETE FROM inventory_service.inventory_reservations WHERE order_id = ?")
                    .param(orderId).update();
        }
        jdbcClient.sql("UPDATE inventory_service.inventory_items SET reserved_quantity = 0 WHERE sku = ?")
                .param(SKU).update();
    }

    @Test
    void listConsumersReportsBothListenersRunning() {
        List<ConsumerState> states = listConsumers();

        assertThat(states).extracting(ConsumerState::name)
                .containsExactly("order-created", "payment-rejected");
        assertThat(states).allSatisfy(state -> {
            assertThat(state.groupId()).isEqualTo("inventory-service");
            assertThat(state.paused()).isFalse();
        });
        assertThat(states).extracting(ConsumerState::topic)
                .containsExactly(KafkaTopics.ORDERS_EVENTS, KafkaTopics.PAYMENTS_EVENTS);
    }

    @Test
    void aPausedListenerHoldsItsBacklogAndDrainsItOnResume() {
        InventoryItemDto before = getInventory(SKU);
        orderId = "order-outage-" + UUID.randomUUID();

        ConsumerState paused = pause();
        assertThat(paused.paused()).isTrue();
        // Read the state back through GET as well, so the pause is observable the way the UI will
        // observe it rather than only in the POST's own response.
        assertThat(listConsumers()).filteredOn(s -> s.name().equals(LISTENER))
                .singleElement().satisfies(s -> assertThat(s.paused()).isTrue());

        publish(orderId, 3);

        // A negative held over time, not sampled once: for five continuous seconds nothing may be
        // reserved. Sampling once could pass simply by looking before the consumer got to it.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(getInventory(SKU).reservedQuantity()).isEqualTo(before.reservedQuantity());
            assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED))
                    .isEmpty();
        });

        ConsumerState resumed = resume();
        assertThat(resumed.paused()).isFalse();

        // The record was retained, not discarded: the same consumer, from its committed offset,
        // now applies the work it could not see while paused.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(getInventory(SKU).reservedQuantity()).isEqualTo(before.reservedQuantity() + 3);
            assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED))
                    .singleElement()
                    .satisfies(r -> assertThat(r.getQuantity()).isEqualTo(3));
        });
    }

    @Test
    void pauseAndResumeAreIdempotentAndUnknownListenersAre404() {
        assertThat(pause().paused()).isTrue();
        assertThat(pause().paused()).isTrue();
        assertThat(resume().paused()).isFalse();
        assertThat(resume().paused()).isFalse();

        client.post().uri("/demo/consumers/no-such-listener/pause").exchange().expectStatus().isNotFound();
    }

    private List<ConsumerState> listConsumers() {
        return client.get().uri("/demo/consumers").exchange()
                .expectBody(new ParameterizedTypeReference<List<ConsumerState>>() {
                }).returnResult().getResponseBody();
    }

    private ConsumerState pause() {
        return client.post().uri("/demo/consumers/" + LISTENER + "/pause").exchange()
                .expectBody(ConsumerState.class).returnResult().getResponseBody();
    }

    private ConsumerState resume() {
        return client.post().uri("/demo/consumers/" + LISTENER + "/resume").exchange()
                .expectBody(ConsumerState.class).returnResult().getResponseBody();
    }

    private InventoryItemDto getInventory(String sku) {
        return client.get().uri("/api/inventory/" + sku).exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();
    }

    private void publish(String orderId, int quantity) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                eventPublisher.publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                        new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem(SKU, quantity)))));
    }
}
