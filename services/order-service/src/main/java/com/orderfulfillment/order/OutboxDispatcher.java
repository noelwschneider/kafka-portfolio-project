package com.orderfulfillment.order;

import com.orderfulfillment.common.kafka.KafkaTopics;
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
 * The read half of ADR-006's transactional outbox: one batch of pending rows, sent to Kafka in
 * insertion order and marked published. Separate from {@link OutboxPublisher} (which owns the
 * {@code @Scheduled} tick) so that {@code @Transactional} actually applies — a self-invoked call
 * would bypass Spring's proxy, the same reason {@link OrderPersistence} is split out of
 * {@link OrderService}.
 *
 * <h2>Ordering</h2>
 *
 * <p>Rows are sent strictly oldest-first and one at a time, blocking on each broker acknowledgement
 * before the next send, because ADR-001's per-partition ordering guarantee is only worth anything
 * if this publisher preserves the order the transactions committed in. That is also why a send
 * failure stops the batch (below) instead of skipping ahead.
 *
 * <h2>Duplicates, not losses</h2>
 *
 * <p>The send and the {@code PUBLISHED} mark are not atomic — a crash in between resends the row on
 * the next tick. That is ADR-006's stated trade: a lost-event problem becomes a duplicate-event
 * problem, and duplicates are the one ADR-005's idempotent consumers already handle. At-least-once,
 * never exactly-once (agent-guidance.md rule 18).
 *
 * <h2>Retry vs FAILED</h2>
 *
 * <p>The frozen schema has no retry-count column, so retries are bounded by <em>age</em> instead: a
 * row whose send fails stays {@code PENDING} and is retried on every tick until it is older than
 * {@code fail-after-ms}, at which point it is marked {@code FAILED}, logged at ERROR, and skipped
 * so it cannot block the queue forever. A broker outage shorter than that window therefore costs
 * nothing but latency; a genuinely unpublishable row (or an outage longer than the window) surfaces
 * as a FAILED row for a human to look at, which is what ADR-006 says that column is for. Nothing
 * here ever deletes or rewrites {@code payload}, so a FAILED row remains a complete record of the
 * event that should have been published.
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

    /**
     * Publishes one batch. Returns the number of rows actually published, so the caller can tell a
     * quiet tick from a productive one.
     *
     * <p>The transaction spans the sends on purpose: it holds {@code FOR UPDATE} on the batch, so a
     * second instance of this service waits its turn rather than interleaving sends. Whatever was
     * published before a failure still commits — the loop returns normally rather than throwing.
     */
    @Transactional
    int publishPendingBatch() {
        List<OutboxEventEntity> pending =
                outboxRepository.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        int published = 0;
        for (OutboxEventEntity row : pending) {
            try {
                kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, row.getAggregateId(), wireForm(row.getPayload()))
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                row.markPublished(Instant.now());
                published++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                if (expired(row)) {
                    row.markFailed();
                    log.error("Outbox row {} ({} for order {}) could not be published within {} — marking FAILED; "
                                    + "this event was never sent and needs manual attention",
                            row.getId(), row.getEventType(), row.getAggregateId(), failAfter, ex);
                    continue; // aged out; skipping it unblocks everything queued behind it
                }
                log.warn("Outbox send failed for row {} ({} for order {}); leaving PENDING for retry",
                        row.getId(), row.getEventType(), row.getAggregateId(), ex);
                break; // stop the batch: sending later rows first would reorder the topic
            }
        }
        return published;
    }

    /**
     * PostgreSQL's {@code jsonb} is a decomposed binary format, not the text that was inserted: it
     * drops insignificant whitespace, reorders object keys and collapses duplicates, so reading the
     * column back gives {@code {"eventId": "…"}} where the producer wrote {@code {"eventId":"…"}}.
     * The two are the same JSON document and every consumer here parses rather than string-matches,
     * but records on {@code orders.events} should look the same whichever service produced them, so
     * the row is re-serialized compactly on its way out. This changes formatting only — every value
     * in the envelope, including eventId/occurredAt/correlationId, is still exactly what the
     * business transaction committed.
     */
    private String wireForm(String storedPayload) {
        return objectMapper.writeValueAsString(objectMapper.readTree(storedPayload));
    }

    private boolean expired(OutboxEventEntity row) {
        return row.getCreatedAt().isBefore(Instant.now().minus(failAfter));
    }
}
