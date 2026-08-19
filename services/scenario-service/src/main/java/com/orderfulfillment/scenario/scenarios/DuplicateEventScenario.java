package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.scenario.clients.OrderServiceClient;
import com.orderfulfillment.scenario.domain.EventRecordEntity;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * docs/scenarios.md Scenario 4 — Duplicate Event Delivery. Runs a normal order, then republishes its
 * real {@code OrderCreated} record to {@code orders.events} with the identical eventId, key, and
 * payload — a genuine second Kafka record, not a UI label, so Inventory Service's own idempotency
 * check (its {@code processed_events} ledger) is what actually suppresses the second reservation.
 */
@Component
public class DuplicateEventScenario extends AbstractScenarioRunner {

    private static final int PROJECTION_POLL_ATTEMPTS = 40;
    private static final long PROJECTION_POLL_INTERVAL_MS = 250L;

    public DuplicateEventScenario(ScenarioToolkit toolkit) {
        super(toolkit);
    }

    @Override
    public String scenarioName() {
        return "duplicate-event";
    }

    @Override
    public void run(ScenarioRunContext ctx) {
        recordHttp(ctx.runId(), "PUT /demo/payment-behavior", paymentServiceClient.setBehavior("DEFAULT_SUCCESS"));

        OrderServiceClient.OrderCreationResult order =
                createOrder(ctx.runId(), "SKU-001", 2, "demo-customer");
        ctx.setPrimaryOrderId().accept(order.orderId());

        EventRecordEntity orderCreated = awaitProjectedOrderCreated(ctx, order.orderId());
        republish(ctx, orderCreated);

        orderStatusWatcher.awaitTerminal(ctx.runId(), order.orderId());
    }

    private EventRecordEntity awaitProjectedOrderCreated(ScenarioRunContext ctx, String orderId) {
        for (int attempt = 0; attempt < PROJECTION_POLL_ATTEMPTS; attempt++) {
            Optional<EventRecordEntity> found = eventRecordRepository
                    .findByCorrelationIdOrderByOccurredAtAsc(ctx.correlationId()).stream()
                    .filter(e -> EventTypes.ORDER_CREATED.equals(e.getEventType()))
                    .filter(e -> orderId.equals(e.getAggregateId()))
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            sleep();
        }
        throw new IllegalStateException(
                "OrderCreated for " + orderId + " was not observed by the event projection in time");
    }

    private void republish(ScenarioRunContext ctx, EventRecordEntity original) {
        JsonNode payloadNode = objectMapper.readTree(original.getPayload());
        EventEnvelope<JsonNode> duplicate = new EventEnvelope<>(
                original.getEventId(), original.getEventType(), original.getEventVersion(),
                original.getOccurredAt(), original.getCorrelationId(), original.getAggregateId(), payloadNode);
        String json = objectMapper.writeValueAsString(duplicate);
        kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, original.getAggregateId(), json);
    }

    private void sleep() {
        try {
            Thread.sleep(PROJECTION_POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
