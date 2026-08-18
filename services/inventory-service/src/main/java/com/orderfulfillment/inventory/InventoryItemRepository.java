package com.orderfulfillment.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryItemRepository extends JpaRepository<InventoryItemEntity, String> {
}
