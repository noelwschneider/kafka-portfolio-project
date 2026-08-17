package com.orderfulfillment.monolith.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistoryEntity, Long> {
    List<OrderStatusHistoryEntity> findByOrderIdOrderByOccurredAtAsc(String orderId);
}
