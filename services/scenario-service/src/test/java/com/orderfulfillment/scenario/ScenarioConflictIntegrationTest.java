package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * docs/openapi/scenario-service.yaml's 409 guard: "two rapid calls to the same scenario name must
 * actually conflict, not just usually happen to". Order Service's response is delayed so the first
 * run is provably still {@code RUNNING} when the second request lands.
 */
class ScenarioConflictIntegrationTest extends AbstractIntegrationTest {

    @Test
    void secondCallToTheSameRunningScenarioIsRejectedWith409() {
        PAYMENT_SERVICE.stubFor(put(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(200)));
        ORDER_SERVICE.stubFor(post(urlPathEqualTo("/api/orders")).willReturn(aResponse().withStatus(201)
                .withFixedDelay(3000)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"order-slow-1\",\"status\":\"PENDING\"}")));

        client.post().uri("/demo/scenarios/standard-order").exchange().expectStatus().isEqualTo(202);

        @SuppressWarnings("unchecked")
        Map<String, Object> conflict = client.post().uri("/demo/scenarios/standard-order")
                .exchange().expectStatus().isEqualTo(409).expectBody(Map.class).returnResult().getResponseBody();
        assertThat(conflict.get("code")).isEqualTo("SCENARIO_ALREADY_RUNNING");
    }

    @Test
    void unknownScenarioNameIs404() {
        client.post().uri("/demo/scenarios/not-a-real-scenario").exchange().expectStatus().isEqualTo(404);
    }

    @Test
    void highVolumeIsNotYetAvailable() {
        client.post().uri("/demo/scenarios/high-volume").exchange().expectStatus().isEqualTo(409);
    }
}
