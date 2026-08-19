package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * docs/scenarios.md Scenario 1 — Standard Fulfillment. Order Service and Payment Service's real HTTP
 * surfaces are stood in by WireMock (see AbstractIntegrationTest); the sequence of statuses
 * GET /api/orders/{id} returns is scripted to walk the real order-state-machine.md happy path, so the
 * assertions here are about Scenario Service's own behavior: it drives the calls in the documented
 * order, records an honest timeline, and reaches COMPLETED with orderId set once the order is FULFILLED.
 */
class StandardOrderScenarioIntegrationTest extends AbstractIntegrationTest {

    @Test
    void standardOrderReachesFulfilledWithARealTimeline() {
        PAYMENT_SERVICE.stubFor(put(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(200)));

        String orderId = "order-std-1";
        ORDER_SERVICE.stubFor(post(urlPathEqualTo("/api/orders")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + orderId + "\",\"status\":\"PENDING\"}")));

        // Walks PENDING -> INVENTORY_RESERVED -> PAYMENT_PENDING -> PAID -> FULFILLMENT_PENDING ->
        // FULFILLED across successive polls, mirroring docs/order-state-machine.md's happy path.
        stubOrderLifecycle(orderId, "PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING", "PAID",
                "FULFILLMENT_PENDING", "FULFILLED");

        @SuppressWarnings("unchecked")
        Map<String, Object> started = client.post().uri("/demo/scenarios/standard-order")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        String runId = String.valueOf(started.get("id"));
        assertThat(started.get("status")).isEqualTo("RUNNING");

        await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> run = client.get().uri("/demo/scenario-runs/" + runId)
                    .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            assertThat(run.get("status")).isEqualTo("COMPLETED");
            assertThat(run.get("orderId")).isEqualTo(orderId);
            @SuppressWarnings("unchecked")
            var timeline = (java.util.List<Map<String, Object>>) run.get("timeline");
            assertThat(timeline).isNotEmpty();
            assertThat(timeline.stream().anyMatch(e -> "HTTP".equals(e.get("kind")))).isTrue();
            assertThat(timeline.stream().anyMatch(e -> "STATE_CHANGE".equals(e.get("kind"))
                    && "FULFILLED".equals(((Map<?, ?>) e.get("detail")).get("status")))).isTrue();
        });
    }
}
