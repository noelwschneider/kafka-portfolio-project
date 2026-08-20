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

/** One transition consumed out of order and parked until its predecessor lands — ADR-009. */
@Entity
@Table(name = "deferred_transitions", schema = "order_service")
public class DeferredTransitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_status", nullable = false)
    private OrderStatus targetStatus;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeferredTransitionStatus status;

    @Column(name = "deferred_at", nullable = false)
    private Instant deferredAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected DeferredTransitionEntity() {
    }

    public DeferredTransitionEntity(String orderId, OrderStatus targetStatus, UUID sourceEventId, Instant deferredAt) {
        this.orderId = orderId;
        this.targetStatus = targetStatus;
        this.sourceEventId = sourceEventId;
        this.status = DeferredTransitionStatus.PENDING;
        this.deferredAt = deferredAt;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public OrderStatus getTargetStatus() {
        return targetStatus;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public DeferredTransitionStatus getStatus() {
        return status;
    }

    public Instant getDeferredAt() {
        return deferredAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    void resolve(DeferredTransitionStatus outcome, Instant at) {
        this.status = outcome;
        this.resolvedAt = at;
    }
}
