package com.orderfulfillment.monolith.order;

import com.orderfulfillment.monolith.common.IdGenerator;
import com.orderfulfillment.monolith.common.NotFoundException;
import com.orderfulfillment.monolith.common.ValidationApiException;
import com.orderfulfillment.monolith.fulfillment.FulfillmentService;
import com.orderfulfillment.monolith.inventory.InventoryService;
import com.orderfulfillment.monolith.inventory.OrderLine;
import com.orderfulfillment.monolith.inventory.ReservationResult;
import com.orderfulfillment.monolith.order.dto.CreateOrderItem;
import com.orderfulfillment.monolith.order.dto.CreateOrderRequest;
import com.orderfulfillment.monolith.order.dto.OrderAccepted;
import com.orderfulfillment.monolith.order.dto.OrderDetail;
import com.orderfulfillment.monolith.order.dto.OrderItemDto;
import com.orderfulfillment.monolith.order.dto.OrderPage;
import com.orderfulfillment.monolith.order.dto.OrderStatusHistoryEntryDto;
import com.orderfulfillment.monolith.order.dto.OrderSummary;
import com.orderfulfillment.monolith.payment.PaymentOutcome;
import com.orderfulfillment.monolith.payment.PaymentService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the order workflow synchronously via direct in-process calls between the domain
 * service beans (order/inventory/payment/fulfillment), driving the same states and transitions
 * docs/order-state-machine.md defines for the eventual Kafka-driven version. This is Phase 1's
 * one deliberate, documented deviation from the frozen OpenAPI examples: POST /api/orders returns
 * the *actual resulting status* (e.g. FULFILLED, REJECTED_OUT_OF_STOCK, PAYMENT_FAILED), not the
 * spec's PENDING example, because there is no Kafka lag yet for the response to arrive ahead of.
 * Phase 2 replacing these calls with real asynchrony is what makes the response match the spec
 * again. See docs/agent-reports/phase-1.md.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderPersistence persistence;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final FulfillmentService fulfillmentService;
    private final SkuPriceCatalog priceCatalog;
    private final IdGenerator idGenerator;

    public OrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                         OrderStatusHistoryRepository historyRepository, OrderPersistence persistence,
                         InventoryService inventoryService, PaymentService paymentService,
                         FulfillmentService fulfillmentService, SkuPriceCatalog priceCatalog,
                         IdGenerator idGenerator) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.historyRepository = historyRepository;
        this.persistence = persistence;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.fulfillmentService = fulfillmentService;
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

        OrderEntity order = persistence.createPendingOrder(orderId, request.customerId(), associated, totalAmount);
        OrderStatus finalStatus = runWorkflow(orderId, request.items(), totalAmount);

        return new OrderAccepted(orderId, finalStatus.name(), order.getCreatedAt());
    }

    private OrderStatus runWorkflow(String orderId, List<CreateOrderItem> items, BigDecimal totalAmount) {
        List<OrderLine> lines = items.stream().map(i -> new OrderLine(i.sku(), i.quantity())).toList();

        ReservationResult reservation = inventoryService.reserve(orderId, lines);
        if (!reservation.success()) {
            persistence.appendStatus(orderId, OrderStatus.REJECTED_OUT_OF_STOCK, null);
            return OrderStatus.REJECTED_OUT_OF_STOCK;
        }
        persistence.appendStatus(orderId, OrderStatus.INVENTORY_RESERVED, null);
        persistence.appendStatus(orderId, OrderStatus.PAYMENT_PENDING, null);

        PaymentOutcome payment = paymentService.authorize(orderId, totalAmount, UUID.randomUUID());
        return switch (payment.kind()) {
            case AUTHORIZED -> {
                persistence.appendStatus(orderId, OrderStatus.PAID, null);
                persistence.appendStatus(orderId, OrderStatus.FULFILLMENT_PENDING, null);
                fulfillmentService.createShipment(orderId);
                persistence.appendStatus(orderId, OrderStatus.FULFILLED, null);
                yield OrderStatus.FULFILLED;
            }
            case REJECTED -> {
                persistence.appendStatus(orderId, OrderStatus.PAYMENT_FAILED, null);
                inventoryService.release(orderId);
                yield OrderStatus.PAYMENT_FAILED;
            }
            case PROVIDER_ERROR -> {
                // No retry/DLQ machinery exists yet (Phase 4). A simulated transient provider
                // error is therefore treated as the FAILED terminal transition (transition 9)
                // rather than left in PAYMENT_PENDING as the full Kafka-driven design intends.
                persistence.appendStatus(orderId, OrderStatus.FAILED, null);
                yield OrderStatus.FAILED;
            }
        };
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
