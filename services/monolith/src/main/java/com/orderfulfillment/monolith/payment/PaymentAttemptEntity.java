package com.orderfulfillment.monolith.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts", schema = "payment_service")
public class PaymentAttemptEntity {

    @Id
    private String id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentAttemptStatus status;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason")
    private PaymentFailureReason failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentAttemptEntity() {
    }

    public PaymentAttemptEntity(String id, String orderId, UUID idempotencyKey, PaymentAttemptStatus status,
                                 BigDecimal amount, PaymentFailureReason failureReason, Instant createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.amount = amount;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentFailureReason getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
