package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * docs/scenarios.md Scenario 7 — Inventory Contention. Real optimistic-locking contention only
 * happens inside the real Inventory Service, which this suite stands in for with WireMock (see
 * AbstractIntegrationTest) — so this test cannot exercise the winner/loser decision itself (that is
 * exercised live and manually per docs/agent-reports/phase-5-scenario-service.md §6). What it does
 * verify is Scenario Service's own responsibility: firing two genuinely concurrent
 * {@code POST /api/orders} calls, keeping each thread's correlation id and timeline entries
 * correctly attributed to its own order, and completing the run once both watches reach their
 * scripted terminal status — with the run's own {@code orderId} left null, since two orders (not
 * one) are involved (see InventoryContentionScenario's class Javadoc).
 */
class InventoryContentionScenarioIntegrationTest extends AbstractIntegrationTest {

    @Test
    void twoConcurrentOrdersAreEachTrackedToTheirOwnOutcome() {
        PAYMENT_SERVICE.stubFor(put(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(200)));

        String winnerOrderId = "order-contention-winner";
        String loserOrderId = "order-contention-loser";
        // WireMock scenario state, not this test's scenario run: the first of the two concurrent
        // POST /api/orders calls to arrive gets the "winner" id, the second gets the "loser" id.
        // Which physical thread arrives first is irrelevant to the assertions below.
        ORDER_SERVICE.stubFor(post(urlPathEqualTo("/api/orders"))
                .inScenario("order-creation-race")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + winnerOrderId + "\",\"status\":\"PENDING\"}"))
                .willSetStateTo("SECOND_CALL"));
        ORDER_SERVICE.stubFor(post(urlPathEqualTo("/api/orders"))
                .inScenario("order-creation-race")
                .whenScenarioStateIs("SECOND_CALL")
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + loserOrderId + "\",\"status\":\"PENDING\"}")));

        stubOrderLifecycle(winnerOrderId, "PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING", "PAID",
                "FULFILLMENT_PENDING", "FULFILLED");
        stubOrderLifecycle(loserOrderId, "PENDING", "REJECTED_OUT_OF_STOCK");

        @SuppressWarnings("unchecked")
        Map<String, Object> started = client.post().uri("/demo/scenarios/inventory-contention")
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
            // Documented in InventoryContentionScenario: left null since two orders, not one, are involved.
            assertThat(run.get("orderId")).isNull();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timeline = (List<Map<String, Object>>) run.get("timeline");
            assertThat(timeline.stream().filter(e -> "HTTP".equals(e.get("kind"))
                    && "POST /api/orders".equals(e.get("label"))).count()).isEqualTo(2);
            assertThat(timeline.stream().anyMatch(e -> "STATE_CHANGE".equals(e.get("kind"))
                    && winnerOrderId.equals(((Map<?, ?>) e.get("detail")).get("orderId"))
                    && "FULFILLED".equals(((Map<?, ?>) e.get("detail")).get("status")))).isTrue();
            assertThat(timeline.stream().anyMatch(e -> "STATE_CHANGE".equals(e.get("kind"))
                    && loserOrderId.equals(((Map<?, ?>) e.get("detail")).get("orderId"))
                    && "REJECTED_OUT_OF_STOCK".equals(((Map<?, ?>) e.get("detail")).get("status")))).isTrue();
        });

        ORDER_SERVICE.verify(2, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/api/orders")));
    }
}
