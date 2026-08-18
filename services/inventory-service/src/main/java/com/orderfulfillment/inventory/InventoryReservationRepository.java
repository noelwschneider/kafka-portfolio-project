package com.orderfulfillment.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservationEntity, String> {
    List<InventoryReservationEntity> findByOrderIdAndStatus(String orderId, ReservationStatus status);
}
