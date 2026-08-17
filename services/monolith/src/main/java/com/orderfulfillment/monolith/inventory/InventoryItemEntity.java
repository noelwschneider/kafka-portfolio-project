package com.orderfulfillment.monolith.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "inventory_items", schema = "inventory_service")
public class InventoryItemEntity {

    @Id
    private String sku;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryItemEntity() {
    }

    public InventoryItemEntity(String sku, String displayName, int availableQuantity, int reservedQuantity, Instant updatedAt) {
        this.sku = sku;
        this.displayName = displayName;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = reservedQuantity;
        this.updatedAt = updatedAt;
    }

    public int freeQuantity() {
        return availableQuantity - reservedQuantity;
    }

    public String getSku() {
        return sku;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public void setAvailableQuantity(int availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public void setReservedQuantity(int reservedQuantity) {
        this.reservedQuantity = reservedQuantity;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
