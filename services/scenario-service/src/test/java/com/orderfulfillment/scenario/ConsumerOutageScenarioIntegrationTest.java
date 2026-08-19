package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * docs/scenarios.md Scenario 5 — Consumer Outage and Recovery. The real backlog-durably-queues-
 * while-paused behavior only happens inside the real Inventory Service listener, which this suite
 * stands in for with WireMock (see AbstractIntegrationTest) — that part is exercised live and
 * manually per docs/agent-reports/phase-5-scenario-service.md §4. What this test verifies is
 * Scenario Service's own responsibility: it calls pause before creating the order, resume after the
 * configured pause window, and completes the run once the order reaches its scripted terminal
 * status — with the run's {@code orderId} set to that single order (unlike
 * inventory-contention's two-order case).
 */
class ConsumerOutageScenarioIntegrationTest extends AbstractIntegrationTest {

    @Test
    void pausesBeforeCreatingAndResumesBeforeWatchingToFulfilled() {
        PAYMENT_SERVICE.stubFor(put(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(200)));
        INVENTORY_SERVICE.stubFor(post(urlPathEqualTo("/demo/consumers/order-created/pause"))
                .willReturn(aResponse().withStatus(200)));
        INVENTORY_SERVICE.stubFor(post(urlPathEqualTo("/demo/consumers/order-created/resume"))
                .willReturn(aResponse().withStatus(200)));

        String orderId = "order-outage-1";
        ORDER_SERVICE.stubFor(post(urlPathEqualTo("/api/orders")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + orderId + "\",\"status\":\"PENDING\"}")));
        stubOrderLifecycle(orderId, "PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING", "PAID",
                "FULFILLMENT_PENDING", "FULFILLED");

        @SuppressWarnings("unchecked")
        Map<String, Object> started = client.post().uri("/demo/scenarios/consumer-outage")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        String runId = String.valueOf(started.get("id"));

        await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> run = client.get().uri("/demo/scenario-runs/" + runId)
                    .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            assertThat(run.get("status")).isEqualTo("COMPLETED");
            assertThat(run.get("orderId")).isEqualTo(orderId);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timeline = (List<Map<String, Object>>) run.get("timeline");
            List<String> httpLabelsInOrder = timeline.stream()
                    .filter(e -> "HTTP".equals(e.get("kind")))
                    .sorted(Comparator.comparingInt(e -> (Integer) e.get("sequence")))
                    .map(e -> (String) e.get("label"))
                    .toList();
            // Pause must precede order creation, and resume must follow it — the whole point of the
            // scenario (docs/scenarios.md Scenario 5).
            assertThat(httpLabelsInOrder).containsSubsequence(
                    "POST /demo/consumers/order-created/pause", "POST /api/orders",
                    "POST /demo/consumers/order-created/resume");
            assertThat(timeline.stream().anyMatch(e -> "STATE_CHANGE".equals(e.get("kind"))
                    && orderId.equals(((Map<?, ?>) e.get("detail")).get("orderId"))
                    && "FULFILLED".equals(((Map<?, ?>) e.get("detail")).get("status")))).isTrue();
        });

        INVENTORY_SERVICE.verify(1, postRequestedFor(urlPathEqualTo("/demo/consumers/order-created/pause")));
        INVENTORY_SERVICE.verify(1, postRequestedFor(urlPathEqualTo("/demo/consumers/order-created/resume")));
    }
}
