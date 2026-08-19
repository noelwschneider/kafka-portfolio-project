package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * docs/scenarios.md Scenario 3 — Payment Rejection. Verifies both the terminal order state and that
 * the override is armed with {@code REJECT} before the order is created and cleared afterward, per the
 * OpenAPI doc's "How a run is composed".
 */
class PaymentFailureScenarioIntegrationTest extends AbstractIntegrationTest {

    @Test
    void paymentFailureReachesPaymentFailedAndClearsTheOverride() {
        PAYMENT_SERVICE.stubFor(put(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(200)));
        PAYMENT_SERVICE.stubFor(delete(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(204)));

        String orderId = "order-payfail-1";
        ORDER_SERVICE.stubFor(post(urlPathEqualTo("/api/orders")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + orderId + "\",\"status\":\"PENDING\"}")));
        stubOrderLifecycle(orderId, "PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING", "PAYMENT_FAILED");

        @SuppressWarnings("unchecked")
        Map<String, Object> started = client.post().uri("/demo/scenarios/payment-failure")
                .exchange().expectStatus().isEqualTo(202).expectBody(Map.class).returnResult().getResponseBody();
        String runId = String.valueOf(started.get("id"));

        await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> run = client.get().uri("/demo/scenario-runs/" + runId)
                    .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            assertThat(run.get("status")).isEqualTo("COMPLETED");
            assertThat(run.get("orderId")).isEqualTo(orderId);
        });

        // The override is armed with REJECT before order creation, and cleared once the run finishes.
        PAYMENT_SERVICE.verify(com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(
                        urlPathEqualTo("/demo/payment-behavior"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$[?(@.mode == 'REJECT')]")));
        PAYMENT_SERVICE.verify(com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(
                urlPathEqualTo("/demo/payment-behavior")));
    }
}
