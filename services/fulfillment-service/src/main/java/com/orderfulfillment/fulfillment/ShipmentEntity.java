package com.orderfulfillment.fulfillment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "shipments", schema = "fulfillment_service")
public class ShipmentEntity {

    @Id
    private String id;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @Column(nullable = false)
    private String status;

    @Column(name = "tracking_number", nullable = false)
    private String trackingNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShipmentEntity() {
    }

    public ShipmentEntity(String id, String orderId, String status, String trackingNumber, Instant createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.status = status;
        this.trackingNumber = trackingNumber;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getStatus() {
        return status;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
