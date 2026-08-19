package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** docs/openapi/scenario-service.yaml's /demo/reset. */
class DemoResetIntegrationTest extends AbstractIntegrationTest {

    @Test
    void resetRestoresSeedInventoryResumesConsumersAndClearsPaymentBehavior() {
        INVENTORY_SERVICE.stubFor(put(urlPathMatching("/api/inventory/SKU-00[1-4]"))
                .willReturn(aResponse().withStatus(200)));
        INVENTORY_SERVICE.stubFor(get(urlPathEqualTo("/demo/consumers")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"name\":\"order-created\",\"topic\":\"orders.events\","
                        + "\"groupId\":\"inventory-service\",\"paused\":true}]")));
        INVENTORY_SERVICE.stubFor(post(urlPathEqualTo("/demo/consumers/order-created/resume"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"order-created\",\"topic\":\"orders.events\","
                                + "\"groupId\":\"inventory-service\",\"paused\":false}")));
        FULFILLMENT_SERVICE.stubFor(get(urlPathEqualTo("/demo/consumers")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[]")));
        PAYMENT_SERVICE.stubFor(delete(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(204)));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = client.post().uri("/demo/reset")
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();

        assertThat(result.get("inventoryRestored")).isEqualTo(true);
        assertThat(result.get("paymentBehaviorCleared")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var resumed = (java.util.List<String>) result.get("consumersResumed");
        assertThat(resumed).contains("inventory-service/order-created");

        INVENTORY_SERVICE.verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/demo/consumers/order-created/resume")));
    }

    @Test
    void resetIsRejectedWhileAScenarioIsRunning() {
        PAYMENT_SERVICE.stubFor(put(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(200)));
        ORDER_SERVICE.stubFor(post(urlPathEqualTo("/api/orders")).willReturn(aResponse().withStatus(201)
                .withFixedDelay(3000).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"order-slow-2\",\"status\":\"PENDING\"}")));

        client.post().uri("/demo/scenarios/standard-order").exchange().expectStatus().isEqualTo(202);

        client.post().uri("/demo/reset").exchange().expectStatus().isEqualTo(409);
    }
}
