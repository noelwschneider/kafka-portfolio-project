package com.orderfulfillment.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Split out from OrderService so each step commits in its own transaction — mirroring what
 * separate REST/event calls will look like once Phase 3 extracts these into different services —
 * and so @Transactional actually applies: a self-invoked call from within OrderService would
 * bypass Spring's proxy (see InventoryReservationExecutor for the same reasoning).
 */
@Component
class OrderPersistence {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository historyRepository;

    OrderPersistence(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                      OrderStatusHistoryRepository historyRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OrderEntity createPendingOrder(String orderId, String customerId, List<OrderItemEntity> items, BigDecimal totalAmount) {
        Instant now = Instant.now();
        OrderEntity order = new OrderEntity(orderId, customerId, OrderStatus.PENDING, totalAmount, now);
        orderRepository.save(order);
        orderItemRepository.saveAll(items);
        historyRepository.save(new OrderStatusHistoryEntity(orderId, OrderStatus.PENDING, null, now));
        return order;
    }

    /** Writes one order_status_history row and moves orders.status — docs/order-state-machine.md §3. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void appendStatus(String orderId, OrderStatus status, UUID sourceEventId) {
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        Instant now = Instant.now();
        order.setStatus(status);
        order.setUpdatedAt(now);
        historyRepository.save(new OrderStatusHistoryEntity(orderId, status, sourceEventId, now));
    }
}
