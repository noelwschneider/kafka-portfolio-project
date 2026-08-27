package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * docs/scenarios.md Scenario 6 — Poison Message / DLQ. Publishes a well-formed envelope — valid
 * {@code eventType}/{@code eventVersion}, so {@link com.orderfulfillment.common.kafka.EventCodec}
 * accepts it — whose payload is missing the required {@code items} field. Inventory Service's consumer
 * decodes it fine and then fails inside {@code InventoryService.reserve} with a NullPointerException
 * when it dereferences the missing list; that exception is not in
 * {@code ConsumerErrorHandlerFactory}'s non-retryable list, so it is genuinely retried three times with
 * backoff (~3.5s) before landing in {@code inventory.dlq} with delivery-attempt metadata — the actual
 * "applies bounded retries with backoff, exhausts them" behavior docs/scenarios.md describes, not a
 * shortcut through an immediately-non-retryable class.
 *
 * <p>Not tied to a live order (docs/scenarios.md's note): the synthetic {@code aggregateId} below is
 * never created via {@code POST /api/orders}, so no order status is expected to change.
 */
@Component
public class PoisonMessageScenario extends AbstractScenarioRunner {

    /** Names the synthetic order this scenario's malformed event references. Not tied to a real
     * {@code POST /api/orders} call — see the class Javadoc — but the id still lands in the projected
     * event record, so it reads unmistakably wherever it surfaces. */
    private static final String CUSTOMER_NAME = "Percy Poison";

    public PoisonMessageScenario(ScenarioToolkit toolkit) {
        super(toolkit);
    }

    @Override
    public String scenarioName() {
        return "poison-message";
    }

    @Override
    public void run(ScenarioRunContext ctx) {
        String syntheticOrderId = "poison-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> incompletePayload = new LinkedHashMap<>();
        incompletePayload.put("orderId", syntheticOrderId);
        incompletePayload.put("customerId", CUSTOMER_NAME);
        // "items" deliberately omitted — this is the unprocessable part of the record.

        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                UUID.randomUUID(), EventTypes.ORDER_CREATED, EventTypes.CURRENT_VERSION, Instant.now(),
                ctx.correlationId(), syntheticOrderId, incompletePayload);
        String json = objectMapper.writeValueAsString(envelope);
        kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, syntheticOrderId, json);

        // Give Inventory Service's bounded retry-then-DLQ policy (~3.5s) time to run its course; the
        // event projection observes the eventual inventory.dlq record on its own and appends its own
        // EVENT timeline entry once it does (EventProjectionConsumer), so nothing further is faked here.
        sleep(6_000L);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
