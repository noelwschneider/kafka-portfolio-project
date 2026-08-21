package com.orderfulfillment.inventory;

import com.orderfulfillment.common.kafka.KafkaTopics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read half of ADR-006's transactional outbox for Inventory Service: one batch of pending rows,
 * sent to {@code inventory.events} in insertion order and marked published. Same shape as Order
 * Service's {@code OutboxDispatcher} — see that class's Javadoc for the full rationale (ordering,
 * duplicates-not-losses, retry-by-age vs FAILED). Split out from {@link OutboxPublisher} for the
 * same proxy reason.
 */
@Component
class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final Duration failAfter;

    OutboxDispatcher(OutboxEventRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                      ObjectMapper objectMapper,
                      @Value("${orderfulfillment.outbox.batch-size:100}") int batchSize,
                      @Value("${orderfulfillment.outbox.send-timeout-ms:10000}") long sendTimeoutMs,
                      @Value("${orderfulfillment.outbox.fail-after-ms:300000}") long failAfterMs) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        this.failAfter = Duration.ofMillis(failAfterMs);
    }

    @Transactional
    int publishPendingBatch() {
        List<OutboxEventEntity> pending =
                outboxRepository.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        int published = 0;
        for (OutboxEventEntity row : pending) {
            try {
                JsonNode envelopeNode = objectMapper.readTree(row.getPayload());
                kafkaTemplate.send(KafkaTopics.INVENTORY_EVENTS, row.getAggregateId(), wireForm(envelopeNode))
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                row.markPublished(Instant.now());
                published++;
                log.info("Published {} for order {} (correlationId={})", row.getEventType(), row.getAggregateId(),
                        envelopeNode.path("correlationId").asText(null));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                if (expired(row)) {
                    row.markFailed();
                    log.error("Outbox row {} ({} for order {}) could not be published within {} — marking FAILED; "
                                    + "this event was never sent and needs manual attention",
                            row.getId(), row.getEventType(), row.getAggregateId(), failAfter, ex);
                    continue;
                }
                log.warn("Outbox send failed for row {} ({} for order {}); leaving PENDING for retry",
                        row.getId(), row.getEventType(), row.getAggregateId(), ex);
                break;
            }
        }
        return published;
    }

    /** See Order Service's {@code OutboxDispatcher#wireForm} — {@code jsonb} normalizes the stored
     * text, so it is re-serialized compactly on the way out; the document itself is unchanged. */
    private String wireForm(JsonNode envelopeNode) {
        return objectMapper.writeValueAsString(envelopeNode);
    }

    private boolean expired(OutboxEventEntity row) {
        return row.getCreatedAt().isBefore(Instant.now().minus(failAfter));
    }
}
