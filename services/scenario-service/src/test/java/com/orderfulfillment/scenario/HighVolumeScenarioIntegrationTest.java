package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * docs/scenarios.md Scenario 8 — High-Volume Batch. As with
 * {@code InventoryContentionScenarioIntegrationTest}, real Inventory Service consumer-group lag
 * only exists against the real Inventory Service, which this suite stands in for with WireMock — so
 * this test cannot exercise real lag numbers (that is exercised live against the real `kind` cluster
 * per docs/agent-reports/phase-10-scaling-demo.md). What it verifies is Scenario Service's own
 * responsibility: bursting {@code orderfulfillment.scenario.high-volume-burst-size} (5 in the test
 * profile) concurrent {@code POST /api/orders} calls, recording the submission-throughput and
 * consumer-lag timeline entries, watching every created order to a terminal status, and completing
 * once all of them are FULFILLED.
 */
class HighVolumeScenarioIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> ORDER_IDS =
            List.of("order-hv-1", "order-hv-2", "order-hv-3", "order-hv-4", "order-hv-5");

    @Test
    void burstOfOrdersReachesFulfilledAndRecordsThroughputAndLag() {
        PAYMENT_SERVICE.stubFor(put(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(200)));
        // The scenario restores its burst SKU to seed stock before submitting (HighVolumeScenario's
        // restoreBurstSkuToSeed) — without it, repeated runs against a real Inventory Service run the
        // SKU down and fail on out-of-stock rather than on anything this scenario is demonstrating.
        INVENTORY_SERVICE.stubFor(post(urlPathEqualTo("/demo/inventory/SKU-003/restore"))
                .willReturn(aResponse().withStatus(200)));

        stubSequentialOrderCreation();
        ORDER_IDS.forEach(id -> ORDER_SERVICE.stubFor(get(urlPathEqualTo("/api/orders/" + id))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + id + "\",\"status\":\"FULFILLED\"}"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> started = client.post().uri("/demo/scenarios/high-volume")
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
            // Documented in HighVolumeScenario: left null since many orders, not one, are involved.
            assertThat(run.get("orderId")).isNull();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timeline = (List<Map<String, Object>>) run.get("timeline");

            Map<String, Object> submission = findByLabel(timeline, "Burst order submission complete");
            assertThat(submission).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> submissionDetail = (Map<String, Object>) submission.get("detail");
            assertThat(((Number) submissionDetail.get("ordersSubmitted")).intValue()).isEqualTo(5);
            assertThat(submissionDetail).containsKeys("submissionDurationMs", "submissionThroughputOrdersPerSec");

            Map<String, Object> lag = findByLabel(timeline, "Consumer lag observed while backlog drains");
            assertThat(lag).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> lagDetail = (Map<String, Object>) lag.get("detail");
            assertThat(lagDetail).containsKeys("samples", "consumerLagAtPeakBacklog", "consumerGroup", "topic");

            Map<String, Object> summary = findByLabel(timeline, "High-volume batch summary");
            assertThat(summary).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> summaryDetail = (Map<String, Object>) summary.get("detail");
            assertThat(((Number) summaryDetail.get("ordersFulfilled")).intValue()).isEqualTo(5);
            assertThat(((Number) summaryDetail.get("ordersNotFulfilled")).intValue()).isZero();
        });

        ORDER_SERVICE.verify(5, postRequestedFor(urlPathEqualTo("/api/orders")));
    }

    private Map<String, Object> findByLabel(List<Map<String, Object>> timeline, String label) {
        return timeline.stream().filter(e -> label.equals(e.get("label"))).findFirst().orElse(null);
    }

    /** WireMock scenario-state chain: the Nth concurrent POST /api/orders to arrive gets the Nth id
     * in {@link #ORDER_IDS} — mirrors InventoryContentionScenarioIntegrationTest's two-step version,
     * extended to 5 steps for this scenario's burst. */
    private void stubSequentialOrderCreation() {
        for (int i = 0; i < ORDER_IDS.size(); i++) {
            String fromState = i == 0 ? com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED : "STATE_" + i;
            var stub = post(urlPathEqualTo("/api/orders"))
                    .inScenario("high-volume-creation")
                    .whenScenarioStateIs(fromState)
                    .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":\"" + ORDER_IDS.get(i) + "\",\"status\":\"PENDING\"}"));
            if (i < ORDER_IDS.size() - 1) {
                stub = stub.willSetStateTo("STATE_" + (i + 1));
            }
            ORDER_SERVICE.stubFor(stub);
        }
    }
}
