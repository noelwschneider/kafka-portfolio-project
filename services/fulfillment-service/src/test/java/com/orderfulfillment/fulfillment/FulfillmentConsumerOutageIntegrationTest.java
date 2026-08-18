package com.orderfulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.kafka.ConsumerState;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.fulfillment.dto.ShipmentDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Scenario 5 — Consumer Outage and Recovery (docs/scenarios.md), Fulfillment Service's half.
 *
 * <p>The outage is produced through the service's real {@code /demo/consumers/{name}/pause} HTTP
 * endpoint — the same call Scenario Service will make — rather than by reaching into
 * {@code KafkaListenerEndpointRegistry} from the test. That matters: the endpoints were frozen in
 * Phase 0 and unimplemented until Phase 4, so a test that bypassed them would leave the thing being
 * shipped untested and would not notice, say, a pause that returns before it has taken effect.
 *
 * <p>Nothing here is a simulated delay. While paused, the record sits durably on
 * {@code payments.events} at an uncommitted offset; on resume the same consumer picks it up from
 * where it left off.
 */
class FulfillmentConsumerOutageIntegrationTest extends AbstractIntegrationTest {

    private static final String LISTENER = "payment-authorized";

    @AfterEach
    void resumeUnconditionally() {
        // Resume unconditionally: a failure between pause and resume would otherwise leave the
        // listener paused and break every test that runs after this one.
        resume();
    }

    @Test
    void listConsumersReportsTheListenerRunning() {
        List<ConsumerState> states = listConsumers();

        assertThat(states).extracting(ConsumerState::name).containsExactly(LISTENER);
        assertThat(states).allSatisfy(state -> {
            assertThat(state.groupId()).isEqualTo("fulfillment-service");
            assertThat(state.paused()).isFalse();
        });
        assertThat(states).extracting(ConsumerState::topic).containsExactly(KafkaTopics.PAYMENTS_EVENTS);
    }

    @Test
    void aPausedListenerHoldsItsBacklogAndDrainsItOnResume() {
        String orderId = "order-outage-" + UUID.randomUUID();

        ConsumerState paused = pause();
        assertThat(paused.paused()).isTrue();
        // Read the state back through GET as well, so the pause is observable the way the UI will
        // observe it rather than only in the POST's own response.
        assertThat(listConsumers()).filteredOn(s -> s.name().equals(LISTENER))
                .singleElement().satisfies(s -> assertThat(s.paused()).isTrue());

        publish(orderId);

        // A negative held over time, not sampled once: for five continuous seconds no shipment may
        // exist. Sampling once could pass simply by looking before the consumer got to it.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(shipmentExists(orderId)).isFalse());

        ConsumerState resumed = resume();
        assertThat(resumed.paused()).isFalse();

        // The record was retained, not discarded: the same consumer, from its committed offset, now
        // applies the work it could not see while paused.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ShipmentDto shipment = getShipment(orderId);
            assertThat(shipment).isNotNull();
            assertThat(shipment.status()).isEqualTo("CREATED");
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

    private boolean shipmentExists(String orderId) {
        Long count = jdbcClient.sql("SELECT count(*) FROM fulfillment_service.shipments WHERE order_id = ?")
                .param(orderId).query(Long.class).single();
        return count != null && count > 0;
    }

    private ShipmentDto getShipment(String orderId) {
        return client.get().uri("/api/shipments/" + orderId).exchange()
                .expectBody(ShipmentDto.class).returnResult().getResponseBody();
    }

    private void publish(String orderId) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                eventPublisher.publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_AUTHORIZED, orderId,
                        new PaymentAuthorizedPayload(orderId, "pay-outage-1", new BigDecimal("42.00"), Instant.now())));
    }
}
