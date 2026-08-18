package com.orderfulfillment.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_status_history", schema = "order_service")
public class OrderStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OrderStatusHistoryEntity() {
    }

    public OrderStatusHistoryEntity(String orderId, OrderStatus status, UUID sourceEventId, Instant occurredAt) {
        this.orderId = orderId;
        this.status = status;
        this.sourceEventId = sourceEventId;
        this.occurredAt = occurredAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
