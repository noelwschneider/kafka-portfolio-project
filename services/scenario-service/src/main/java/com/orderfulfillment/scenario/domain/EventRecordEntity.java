package com.orderfulfillment.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The general-purpose event projection added in Phase 5 to resolve docs/db-ownership.md §4's
 * "Event Explorer's backing store has no owner yet" — see V2__events.sql and
 * docs/agent-reports/phase-5-scenario-service.md for the rationale. One row per Kafka record actually
 * consumed by {@code EventProjectionConsumer}; every column is something that consumer can observe
 * directly, never data reached from another service's schema.
 */
@Entity
@Table(name = "events", schema = "scenario_service")
public class EventRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String topic;

    @Column(name = "\"partition\"", nullable = false)
    private int partition;

    @Column(name = "\"offset\"", nullable = false)
    private long offset;

    @Column(nullable = false)
    private String producer;

    @Column(name = "dead_lettered", nullable = false)
    private boolean deadLettered;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected EventRecordEntity() {
    }

    public EventRecordEntity(UUID eventId, String eventType, int eventVersion, Instant occurredAt,
                              UUID correlationId, String aggregateId, String topic, int partition,
                              long offset, String producer, boolean deadLettered, String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.occurredAt = occurredAt;
        this.correlationId = correlationId;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.producer = producer;
        this.deadLettered = deadLettered;
        this.payload = payload;
        this.recordedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getTopic() {
        return topic;
    }

    public int getPartition() {
        return partition;
    }

    public long getOffset() {
        return offset;
    }

    public String getProducer() {
        return producer;
    }

    public boolean isDeadLettered() {
        return deadLettered;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
