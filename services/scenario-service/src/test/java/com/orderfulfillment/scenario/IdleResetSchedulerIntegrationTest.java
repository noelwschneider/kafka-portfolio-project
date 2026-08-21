package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderfulfillment.scenario.runtime.RunRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * W4 (docs/agent-reports/sprint-2/deployment-code-changes-briefing.md): the scheduled idle
 * auto-reset in {@code com.orderfulfillment.scenario.admin.IdleResetScheduler}. Runs against a real
 * Spring context with the feature turned on and a short idle period/check interval (via
 * {@link DynamicPropertySource}) so the test doesn't need to wait the real 15-minute default.
 *
 * <p>"A scenario run is still RUNNING" is simulated directly through {@link RunRegistry} — the same
 * in-memory guard {@code IdleResetScheduler} itself consults — rather than driving a full scenario
 * run end-to-end; the downstream HTTP calls a real {@code DemoResetService.reset()} makes are
 * stubbed via WireMock exactly as {@link DemoResetIntegrationTest} already does for the manual
 * {@code POST /demo/reset} path this scheduler reuses.
 */
class IdleResetSchedulerIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void idleResetProperties(DynamicPropertyRegistry registry) {
        registry.add("orderfulfillment.idle-reset.enabled", () -> "true");
        registry.add("orderfulfillment.idle-reset.idle-period-ms", () -> "1200");
        registry.add("orderfulfillment.idle-reset.check-interval-ms", () -> "200");
    }

    @Autowired
    RunRegistry runRegistry;

    @Test
    void resumesConsumerAndClearsPaymentOverrideAfterIdlePeriodButNotWhileARunIsInProgress()
            throws InterruptedException {
        // Claim the "a scenario is RUNNING" slot the same way a real run would, without needing to
        // drive one end-to-end. IdleResetScheduler's very first guard checks exactly this registry —
        // claimed *before* any WireMock stub exists, so even a scheduled tick firing this instant
        // (the idle period is intentionally shorter than context startup) cannot race its way past
        // the guard and reach a downstream call.
        String scenarioName = "fake-running-scenario";
        UUID correlationId = UUID.randomUUID();
        boolean claimed = runRegistry.tryStart(scenarioName, "fake-run-id", correlationId);
        assertThat(claimed).isTrue();

        INVENTORY_SERVICE.stubFor(post(urlPathMatching("/demo/inventory/SKU-00[1-4]/restore"))
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

        // Clear the request journal: a scheduled tick landing between context startup and this line
        // (before every stub above existed) could have hit an unstubbed endpoint and left a stray
        // entry, which would make the "no effect while RUNNING" assertions below unreliable for a
        // reason unrelated to what this test is actually checking.
        INVENTORY_SERVICE.resetRequests();
        FULFILLMENT_SERVICE.resetRequests();
        PAYMENT_SERVICE.resetRequests();

        try {
            // Long enough that, without the RUNNING guard, idle-reset (idle-period-ms=1200, checked
            // every 200ms) would already have fired several times over.
            Thread.sleep(1800);

            INVENTORY_SERVICE.verify(0, postRequestedFor(urlPathEqualTo("/demo/consumers/order-created/resume")));
            PAYMENT_SERVICE.verify(0, deleteRequestedFor(urlPathEqualTo("/demo/payment-behavior")));
        } finally {
            runRegistry.finish(scenarioName, correlationId);
        }

        // With the run finished, the next scheduled check should find no RUNNING run and (since the
        // idle period has long since elapsed by this point) trigger a reset.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                INVENTORY_SERVICE.verify(postRequestedFor(urlPathEqualTo("/demo/consumers/order-created/resume"))));
        PAYMENT_SERVICE.verify(deleteRequestedFor(urlPathEqualTo("/demo/payment-behavior")));
    }
}
