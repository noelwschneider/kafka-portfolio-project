package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** docs/scenarios.md Scenario 2 — Out of Stock. */
class OutOfStockScenarioIntegrationTest extends AbstractIntegrationTest {

    @Test
    void outOfStockReachesRejectedOutOfStock() {
        String orderId = "order-oos-1";
        ORDER_SERVICE.stubFor(post(urlPathEqualTo("/api/orders")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + orderId + "\",\"status\":\"PENDING\"}")));
        stubOrderLifecycle(orderId, "PENDING", "REJECTED_OUT_OF_STOCK");

        @SuppressWarnings("unchecked")
        Map<String, Object> started = client.post().uri("/demo/scenarios/out-of-stock")
                .exchange().expectStatus().isEqualTo(202).expectBody(Map.class).returnResult().getResponseBody();
        String runId = String.valueOf(started.get("id"));

        await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> run = client.get().uri("/demo/scenario-runs/" + runId)
                    .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            assertThat(run.get("status")).isEqualTo("COMPLETED");
            assertThat(run.get("orderId")).isEqualTo(orderId);
        });
    }
}
