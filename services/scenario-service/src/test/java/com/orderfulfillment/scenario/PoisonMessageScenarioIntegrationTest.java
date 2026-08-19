package com.orderfulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.kafka.KafkaTopics;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * docs/scenarios.md Scenario 6 — Poison Message / DLQ. This suite has no real Inventory Service
 * (see AbstractIntegrationTest), so the DLQ landing itself is verified in the live full-stack run
 * (docs/agent-reports/phase-5-scenario-service.md); what belongs to Scenario Service and is verified
 * here is that it puts a genuinely unprocessable — but envelope-valid — record on the wire: a
 * well-formed {@code OrderCreated} envelope whose payload is missing the required {@code items} field.
 */
class PoisonMessageScenarioIntegrationTest extends AbstractIntegrationTest {

    @Test
    void publishesAWellFormedEnvelopeWithAnUnprocessablePayload() {
        Consumer<String, String> consumer = rawConsumer(KafkaTopics.ORDERS_EVENTS);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> started = client.post().uri("/demo/scenarios/poison-message")
                    .exchange().expectStatus().isEqualTo(202).expectBody(Map.class).returnResult().getResponseBody();
            String correlationId = (String) started.get("correlationId");

            String[] found = new String[1];
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                for (var r : records) {
                    if (r.value().contains(correlationId) && r.value().contains("\"eventType\":\"OrderCreated\"")) {
                        found[0] = r.value();
                        return true;
                    }
                }
                return false;
            });

            assertThat(found[0]).contains("\"eventVersion\":1");
            assertThat(found[0]).doesNotContain("\"items\"");
        } finally {
            consumer.close();
        }
    }
}
