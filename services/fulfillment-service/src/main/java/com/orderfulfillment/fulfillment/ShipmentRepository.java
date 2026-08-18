package com.orderfulfillment.fulfillment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<ShipmentEntity, String> {
    Optional<ShipmentEntity> findByOrderId(String orderId);
}
