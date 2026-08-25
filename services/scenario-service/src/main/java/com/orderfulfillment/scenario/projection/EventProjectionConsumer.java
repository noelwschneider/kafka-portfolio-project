package com.orderfulfillment.scenario.projection;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.scenario.domain.EventRecordEntity;
import com.orderfulfillment.scenario.domain.EventRecordRepository;
import com.orderfulfillment.scenario.domain.TimelineKind;
import com.orderfulfillment.scenario.runtime.RunRegistry;
import com.orderfulfillment.scenario.runtime.TimelineRecorder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scenario Service's general-purpose event projection — resolves docs/db-ownership.md §4's "Event
 * Explorer's backing store has no owner yet" (see docs/agent-reports/phase-5-scenario-service.md and
 * docs/CHANGELOG-contracts.md). Consumes all four domain topics and all four DLQ topics, in its own
 * consumer group ({@code scenario-service-projection}, set in application.yml) so it never competes
 * for partitions or offsets with a real domain consumer, and never affects delivery to the services
 * that actually own the business logic.
 *
 * <p><b>Honesty boundary (per the timeline schema's own rule, "do not fabricate these fields"):</b>
 * this consumer records only what a direct Kafka consumer can genuinely observe about a
 * <em>published</em> record — topic, partition, offset, eventId, correlationId, aggregateId, and the
 * publisher (from the frozen topic-ownership table, docs/events/event-catalog.md §2). It does
 * deliberately <b>not</b> record a "consumed" phase, a {@code durationMs}, or a {@code retryCount}:
 * those live inside each service's own {@code processed_events} row, which this service may not read
 * (db-ownership.md's one-owner rule forbids cross-schema queries). Fabricating a plausible-looking
 * consumption entry here would violate the "absent, not zero or empty" rule the timeline schema states
 * explicitly. See the report for the two honest alternatives considered and why this one was chosen.
 */
@Component
public class EventProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventProjectionConsumer.class);

    private static final Map<String, String> PRODUCER_BY_TOPIC = Map.of(
            KafkaTopics.ORDERS_EVENTS, "order-service",
            KafkaTopics.INVENTORY_EVENTS, "inventory-service",
            KafkaTopics.PAYMENTS_EVENTS, "payment-service",
            KafkaTopics.FULFILLMENT_EVENTS, "fulfillment-service",
            // A DLQ record is written by the failing *consumer*, not the original publisher
            // (event-catalog.md §2's routing-target note).
            KafkaTopics.ORDERS_DLQ, "order-service",
            KafkaTopics.INVENTORY_DLQ, "inventory-service",
            KafkaTopics.PAYMENTS_DLQ, "payment-service",
            KafkaTopics.FULFILLMENT_DLQ, "fulfillment-service");

    private final EventCodec eventCodec;
    private final EventRecordRepository eventRecordRepository;
    private final ObjectMapper objectMapper;
    private final RunRegistry runRegistry;
    private final TimelineRecorder timelineRecorder;

    public EventProjectionConsumer(EventCodec eventCodec, EventRecordRepository eventRecordRepository,
                                    ObjectMapper objectMapper, RunRegistry runRegistry,
                                    TimelineRecorder timelineRecorder) {
        this.eventCodec = eventCodec;
        this.eventRecordRepository = eventRecordRepository;
        this.objectMapper = objectMapper;
        this.runRegistry = runRegistry;
        this.timelineRecorder = timelineRecorder;
    }

    @KafkaListener(
            id = "scenario-projection-domain",
            groupId = "scenario-service-projection",
            topics = {KafkaTopics.ORDERS_EVENTS, KafkaTopics.INVENTORY_EVENTS,
                    KafkaTopics.PAYMENTS_EVENTS, KafkaTopics.FULFILLMENT_EVENTS})
    @Transactional
    public void onDomainRecord(ConsumerRecord<String, String> record) {
        project(record, false);
    }

    @KafkaListener(
            id = "scenario-projection-dlq",
            groupId = "scenario-service-projection",
            topics = {KafkaTopics.ORDERS_DLQ, KafkaTopics.INVENTORY_DLQ,
                    KafkaTopics.PAYMENTS_DLQ, KafkaTopics.FULFILLMENT_DLQ})
    @Transactional
    public void onDlqRecord(ConsumerRecord<String, String> record) {
        project(record, true);
    }

    /**
     * Not itself {@code @Transactional} — {@code @KafkaListener} is the actual Spring-proxied entry
     * point, so the transaction boundary lives on {@link #onDomainRecord} / {@link #onDlqRecord}
     * (a same-class call to a {@code @Transactional} method here would silently not be proxied).
     */
    void project(ConsumerRecord<String, String> record, boolean deadLettered) {
        EventEnvelope<JsonNode> envelope;
        try {
            envelope = eventCodec.decode(record.value());
        } catch (Exception e) {
            log.warn("Skipping unprojectable record on {}-{}@{}: {}",
                    record.topic(), record.partition(), record.offset(), e.getMessage());
            return;
        }
        // Idempotent by (topic, partition, offset, eventId), not by physical coordinates alone:
        // at-least-once delivery (event-catalog.md §2) can genuinely redeliver the same physical
        // record — a rebalance, a retry after a transient DB error — and re-projecting it must be a
        // safe no-op rather than a constraint-violation crash loop, which is what the physical
        // coordinates alone are for. But (topic, partition, offset) is only a stable identity within
        // one broker epoch: a local stack rebuild against a non-persistent Kafka (or a genuine topic
        // recreation) resets offsets to 0, and dedupe-by-coordinates-only then collides a genuinely
        // new record against a stale row left over from before the reset and silently drops it
        // (sprint-5 issue #27). Requiring the eventId to also match is what tells apart "the same
        // physical record, redelivered" (same tuple, safe no-op) from "a new record that happens to
        // reuse an old physical address" (same coordinates, different eventId, must be projected).
        // Deduping on eventId alone was tried and rejected: docs/scenarios.md's Scenario 4 (a frozen
        // contract) deliberately republishes a record with the same eventId at a genuinely new
        // offset and requires the timeline to show it twice — an eventId-only key would swallow that
        // legitimate second delivery. See V3__events_dedupe_by_topic_partition_offset_and_event_id.sql.
        if (eventRecordRepository.existsByTopicAndPartitionAndOffsetAndEventId(
                record.topic(), record.partition(), record.offset(), envelope.eventId())) {
            log.debug("Already projected {}-{}@{} eventId={}, skipping redelivery", record.topic(),
                    record.partition(), record.offset(), envelope.eventId());
            return;
        }
        String producer = PRODUCER_BY_TOPIC.getOrDefault(record.topic(), "unknown");
        Map<String, Object> payloadMap = toMap(envelope.payload());
        EventRecordEntity entity = new EventRecordEntity(
                envelope.eventId(), envelope.eventType(), envelope.eventVersion(), envelope.occurredAt(),
                envelope.correlationId(), envelope.aggregateId(), record.topic(), record.partition(),
                record.offset(), producer, deadLettered, objectMapper.writeValueAsString(payloadMap));
        eventRecordRepository.save(entity);

        runRegistry.runIdForCorrelation(envelope.correlationId()).ifPresent(runId -> {
            CorrelationIdHolder.runInScope(envelope.correlationId(), () -> {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("phase", "published");
                detail.put("topic", record.topic());
                detail.put("partition", record.partition());
                detail.put("offset", record.offset());
                detail.put("eventId", envelope.eventId().toString());
                detail.put("correlationId", envelope.correlationId().toString());
                detail.put("aggregateId", envelope.aggregateId());
                detail.put("producer", producer);
                if (deadLettered) {
                    detail.put("deadLettered", true);
                }
                timelineRecorder.append(runId, TimelineKind.EVENT, envelope.eventType(), detail);
            });
        });
    }

    private Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, new tools.jackson.core.type.TypeReference<>() {
        });
    }
}
