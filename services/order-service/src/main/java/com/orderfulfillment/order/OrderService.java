package com.orderfulfillment.order;

import com.orderfulfillment.common.IdGenerator;
import com.orderfulfillment.common.NotFoundException;
import com.orderfulfillment.common.ValidationApiException;
import com.orderfulfillment.order.dto.CreateOrderItem;
import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import com.orderfulfillment.order.dto.OrderDetail;
import com.orderfulfillment.order.dto.OrderItemDto;
import com.orderfulfillment.order.dto.OrderPage;
import com.orderfulfillment.order.dto.OrderStatusHistoryEntryDto;
import com.orderfulfillment.order.dto.OrderSummary;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry point for {@code POST /api/orders}. Phase 2: persists the order as PENDING, records
 * OrderCreated for publication (since Phase 6, in the outbox — ADR-006), and returns — it does not wait for inventory, payment, or fulfillment. That
 * happens because Inventory/Payment/Fulfillment now react to Kafka events (see the {@code kafka}
 * subpackage's consumers) rather than being called directly from here, so this class no longer
 * knows or cares how the order eventually resolves. This finally matches
 * docs/openapi/order-service.yaml's POST /api/orders description; Phase 1's synchronous version
 * (which returned the actual terminal status) is documented as a deliberate, temporary deviation
 * in docs/agent-reports/phase-1.md §3.9.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderPersistence persistence;
    private final SkuPriceCatalog priceCatalog;
    private final IdGenerator idGenerator;

    public OrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                         OrderStatusHistoryRepository historyRepository, OrderPersistence persistence,
                         SkuPriceCatalog priceCatalog, IdGenerator idGenerator) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.historyRepository = historyRepository;
        this.persistence = persistence;
        this.priceCatalog = priceCatalog;
        this.idGenerator = idGenerator;
    }

    public OrderAccepted createOrder(CreateOrderRequest request) {
        validateNoDuplicateSkus(request.items());
        List<OrderItemEntity> priced = priceItems(request.items());
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItemEntity item : priced) {
            totalAmount = totalAmount.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        String orderId = idGenerator.nextOrderId();
        List<OrderItemEntity> associated = priced.stream()
                .map(i -> new OrderItemEntity(orderId, i.getSku(), i.getQuantity(), i.getUnitPrice()))
                .toList();

        // OrderCreated is recorded in the outbox inside this call's transaction (ADR-006), not
        // published from here afterwards — the crash window between the two no longer exists.
        OrderEntity order = persistence.createPendingOrder(orderId, request.customerId(), associated, totalAmount);

        return new OrderAccepted(orderId, order.getStatus().name(), order.getCreatedAt());
    }

    private void validateNoDuplicateSkus(List<CreateOrderItem> items) {
        long distinctCount = items.stream().map(CreateOrderItem::sku).distinct().count();
        if (distinctCount != items.size()) {
            throw new ValidationApiException("INVALID_ORDER", "A SKU may appear at most once per order");
        }
    }

    private List<OrderItemEntity> priceItems(List<CreateOrderItem> items) {
        return items.stream().map(item -> {
            BigDecimal price = priceCatalog.priceFor(item.sku());
            if (price == null) {
                throw new ValidationApiException("UNKNOWN_SKU", "No price known for SKU " + item.sku());
            }
            return new OrderItemEntity(null, item.sku(), item.quantity(), price);
        }).toList();
    }

    @Transactional(readOnly = true)
    public OrderDetail getOrder(String orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "No order with id " + orderId));
        List<OrderItemDto> items = orderItemRepository.findByOrderId(orderId).stream()
                .map(i -> new OrderItemDto(i.getSku(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        List<OrderStatusHistoryEntryDto> history = historyRepository.findByOrderIdOrderByOccurredAtAsc(orderId).stream()
                .map(h -> new OrderStatusHistoryEntryDto(h.getStatus().name(), h.getSourceEventId(), h.getOccurredAt()))
                .toList();
        return new OrderDetail(order.getId(), order.getCustomerId(), order.getStatus().name(), order.getTotalAmount(),
                order.getCreatedAt(), order.getUpdatedAt(), items, history);
    }

    @Transactional(readOnly = true)
    public OrderPage listOrders(String statusFilter, String customerId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        OrderStatus status = statusFilter != null ? parseStatus(statusFilter) : null;

        Page<OrderEntity> result;
        if (status != null && customerId != null) {
            result = orderRepository.findByStatusAndCustomerIdOrderByCreatedAtDesc(status, customerId, pageRequest);
        } else if (status != null) {
            result = orderRepository.findByStatusOrderByCreatedAtDesc(status, pageRequest);
        } else if (customerId != null) {
            result = orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageRequest);
        } else {
            result = orderRepository.findAllByOrderByCreatedAtDesc(pageRequest);
        }

        List<OrderSummary> content = result.getContent().stream()
                .map(o -> new OrderSummary(o.getId(), o.getCustomerId(), o.getStatus().name(), o.getTotalAmount(),
                        o.getCreatedAt(), o.getUpdatedAt()))
                .toList();
        return new OrderPage(content, page, size, result.getTotalElements(), result.getTotalPages());
    }

    private OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new ValidationApiException("VALIDATION_ERROR", "Unknown status " + status);
        }
    }
}
