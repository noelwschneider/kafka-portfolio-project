package com.orderfulfillment.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One pending (or already handled) outbound event, written in the same local transaction as the
 * business change it describes — V6__outbox_events.sql, ADR-006. Inventory Service's copy of Order
 * Service's {@code OutboxEventEntity}; see that class's Javadoc for the full rationale.
 *
 * <p>{@code payload} is the already-serialized {@link com.orderfulfillment.common.events.EventEnvelope},
 * kept as a {@code String} so that {@link OutboxDispatcher} sends the document the business
 * transaction committed rather than rebuilding one at send time.
 */
@Entity
@Table(name = "outbox_events", schema = "inventory_service")
class OutboxEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    protected OutboxEventEntity() {
    }

    OutboxEventEntity(String aggregateId, String eventType, String payload, Instant createdAt) {
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
        this.status = OutboxStatus.PENDING;
    }

    Long getId() {
        return id;
    }

    String getAggregateId() {
        return aggregateId;
    }

    String getEventType() {
        return eventType;
    }

    String getPayload() {
        return payload;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    OutboxStatus getStatus() {
        return status;
    }

    void markPublished(Instant publishedAt) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    /** No {@code published_at} is set: the row's whole point is that it never reached Kafka. */
    void markFailed() {
        this.status = OutboxStatus.FAILED;
    }
}
