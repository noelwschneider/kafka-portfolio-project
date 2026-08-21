package com.orderfulfillment.order;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements docs/order-state-machine.md transition 9 (any non-terminal → {@code FAILED}): a
 * listener on this service's own {@code orders.dlq}, the terminal sink every one of Order Service's
 * three domain-topic listeners routes a record to once retries are exhausted or the failure is
 * classified non-retryable ({@link com.orderfulfillment.common.kafka.ConsumerErrorHandlerFactory}).
 * Before this class existed, ADR-009's "Accepted costs" section named this gap explicitly:
 * "Transition 9 (→ FAILED) remains unimplemented" — a dead-lettered record left its order stuck at
 * whatever status it last reached, with no signal that anything had gone wrong.
 *
 * <p>The record's key is the order's id (every producer in this project keys by {@code aggregateId}
 * = {@code orderId}, docs/events/event-catalog.md §2, and {@code DeadLetterPublishingRecoverer}
 * preserves the key), so no decoding is needed to know which order to fail — which matters because
 * the record's <em>value</em> may be exactly the poison bytes that got it dead-lettered in the first
 * place and may not parse at all. {@link OrderPersistence#markFailed} does the actual transition and
 * documents why it needs no {@code processed_events} claim.
 *
 * <p><b>This listener must never let an exception escape {@link #onDeadLettered}.</b> It shares
 * {@link OrderKafkaReliabilityConfig}'s one error handler with every other listener in this service,
 * and that handler's recovery action is "publish to {@code orders.dlq}" — the very topic this
 * listener consumes. An exception thrown here would therefore retry and then republish the record
 * onto its own input topic, an infinite loop. Every failure path below is caught and logged instead.
 */
@Component
class OrderDeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderDeadLetterConsumer.class);

    private final OrderPersistence orderPersistence;
    private final ObjectMapper objectMapper;

    OrderDeadLetterConsumer(OrderPersistence orderPersistence, ObjectMapper objectMapper) {
        this.orderPersistence = orderPersistence;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = OrderConsumers.DEAD_LETTER_LISTENER_ID,
            topics = KafkaTopics.ORDERS_DLQ, groupId = OrderConsumers.DEAD_LETTER_GROUP_ID)
    public void onDeadLettered(ConsumerRecord<String, String> record) {
        String orderId = record.key();
        if (orderId == null || orderId.isBlank()) {
            log.error("Dead-lettered record on {}-{}@{} has no key; cannot attribute it to an order "
                            + "for the FAILED transition (docs/order-state-machine.md transition 9)",
                    record.topic(), record.partition(), record.offset());
            return;
        }
        CorrelationIdHolder.runInScope(bestEffortCorrelationId(record), () -> {
            try {
                orderPersistence.markFailed(orderId);
            } catch (Exception ex) {
                // Deliberately swallowed — see class Javadoc: this must never propagate.
                log.error("Could not apply the FAILED transition for order {} from a dead-lettered "
                                + "record ({}-{}@{}); this order may be left stuck at its previous status "
                                + "and needs manual attention",
                        orderId, record.topic(), record.partition(), record.offset(), ex);
            }
        });
    }

    /**
     * A dead-lettered record's value is the original envelope whenever the failure was something
     * other than the bytes themselves failing to parse (e.g. an unknown {@code eventVersion}), so
     * the original {@code correlationId} is recovered when possible — logs and the SSE stream for
     * this transition then still tie back to the workflow that produced the failing event. When the
     * value genuinely will not parse, a fresh id is used rather than leaving none in scope at all
     * ({@code EventPublisher} and MDC logging both expect one).
     */
    private UUID bestEffortCorrelationId(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());
            JsonNode correlationId = node.get("correlationId");
            if (correlationId != null && !correlationId.isNull()) {
                return UUID.fromString(correlationId.asString());
            }
        } catch (Exception ignored) {
            // Not parseable, or not a UUID — fall through to a fresh id.
        }
        return UUID.randomUUID();
    }
}
