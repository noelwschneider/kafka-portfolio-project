package com.orderfulfillment.order;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.idempotency.ProcessedEventKey;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Split out from OrderService so each step commits in its own transaction — mirroring what
 * separate REST/event calls will look like once Phase 3 extracts these into different services —
 * and so @Transactional actually applies: a self-invoked call from within OrderService would
 * bypass Spring's proxy (see InventoryReservationExecutor for the same reasoning).
 *
 * <p>This is also where each Kafka-driven transition's {@code processed_events} claim happens, for
 * the same reason InventoryReservationExecutor's claim lives inside {@code attemptReserve} rather
 * than in the listener: ADR-005 requires the ledger row and the business change to commit in one
 * local transaction, and these {@code REQUIRES_NEW} methods, not the listeners that call them, are
 * that transaction. Two of the five methods below apply two status rows for one inbound event
 * (transitions 2+4 and 5+7 — see docs/order-state-machine.md's "Notes on the internal transitions")
 * and the claim covers both writes atomically: either the whole pair commits with the ledger row,
 * or a losing/duplicate delivery gets neither.
 */
@Component
class OrderPersistence {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final ProcessedEventLedger processedEventLedger;
    private final ApplicationEventPublisher eventPublisher;

    OrderPersistence(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                      OrderStatusHistoryRepository historyRepository, ProcessedEventLedger processedEventLedger,
                      ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.historyRepository = historyRepository;
        this.processedEventLedger = processedEventLedger;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OrderEntity createPendingOrder(String orderId, String customerId, List<OrderItemEntity> items, BigDecimal totalAmount) {
        Instant now = Instant.now();
        OrderEntity order = new OrderEntity(orderId, customerId, OrderStatus.PENDING, totalAmount, now);
        orderRepository.save(order);
        orderItemRepository.saveAll(items);
        historyRepository.save(new OrderStatusHistoryEntity(orderId, OrderStatus.PENDING, null, now));
        publishStatusChanged(orderId, OrderStatus.PENDING, null, null, now);
        return order;
    }

    /**
     * A single status transition, for the three listener branches that write exactly one
     * {@code order_status_history} row per inbound event: InventoryReservationFailed,
     * PaymentRejected, ShipmentCreated.
     *
     * @param eventKey the event driving this transition, or {@code null} for a call that does not
     *                 originate from a Kafka record and so has nothing to deduplicate against
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    StatusTransitionResult appendStatus(String orderId, OrderStatus status, UUID sourceEventId, ProcessedEventKey eventKey) {
        if (eventKey != null && !processedEventLedger.recordProcessed(eventKey)) {
            return StatusTransitionResult.asDuplicate();
        }
        writeStatus(orderId, status, sourceEventId);
        return StatusTransitionResult.asApplied();
    }

    /**
     * InventoryReserved drives transition 2 ({@code INVENTORY_RESERVED}) and, in the same local
     * transaction, internal transition 4 ({@code PAYMENT_PENDING}) — docs/order-state-machine.md.
     * Returns the order's total amount so the caller can publish {@code PaymentRequested} after
     * this transaction commits, without a second read.
     *
     * @param eventKey as {@link #appendStatus} — the InventoryReserved event being applied, or null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    StatusTransitionResult appendInventoryReservedTransition(String orderId, UUID sourceEventId, ProcessedEventKey eventKey) {
        if (eventKey != null && !processedEventLedger.recordProcessed(eventKey)) {
            return StatusTransitionResult.asDuplicate();
        }
        writeStatus(orderId, OrderStatus.INVENTORY_RESERVED, sourceEventId);
        OrderEntity order = writeStatus(orderId, OrderStatus.PAYMENT_PENDING, null);
        return StatusTransitionResult.asApplied(order.getTotalAmount());
    }

    /**
     * PaymentAuthorized drives transition 5 ({@code PAID}) and, in the same local transaction,
     * internal transition 7 ({@code FULFILLMENT_PENDING}) — docs/order-state-machine.md. No event
     * is published from this transition (Fulfillment Service consumes PaymentAuthorized directly),
     * so unlike {@link #appendInventoryReservedTransition} there is nothing to hand back.
     *
     * @param eventKey as {@link #appendStatus} — the PaymentAuthorized event being applied, or null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    StatusTransitionResult appendPaymentAuthorizedTransition(String orderId, UUID sourceEventId, ProcessedEventKey eventKey) {
        if (eventKey != null && !processedEventLedger.recordProcessed(eventKey)) {
            return StatusTransitionResult.asDuplicate();
        }
        writeStatus(orderId, OrderStatus.PAID, sourceEventId);
        writeStatus(orderId, OrderStatus.FULFILLMENT_PENDING, null);
        return StatusTransitionResult.asApplied();
    }

    /** Writes one order_status_history row and moves orders.status — docs/order-state-machine.md §3. */
    private OrderEntity writeStatus(String orderId, OrderStatus status, UUID sourceEventId) {
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        OrderStatus previousStatus = order.getStatus();
        Instant now = Instant.now();
        order.setStatus(status);
        order.setUpdatedAt(now);
        historyRepository.save(new OrderStatusHistoryEntity(orderId, status, sourceEventId, now));
        publishStatusChanged(orderId, status, previousStatus, sourceEventId, now);
        return order;
    }

    /**
     * Publishes {@link OrderStatusChangedEvent} for {@link OrderStatusStreamListener} to pick up
     * once (and only if) this REQUIRES_NEW transaction actually commits — see that class's Javadoc.
     * Called from inside every write above, never from a listener/controller, so the SSE stream
     * only ever reports transitions the database durably has.
     */
    private void publishStatusChanged(String orderId, OrderStatus status, OrderStatus previousStatus,
                                       UUID sourceEventId, Instant occurredAt) {
        eventPublisher.publishEvent(new OrderStatusChangedEvent(
                orderId, status, previousStatus, sourceEventId, CorrelationIdHolder.get(), occurredAt));
    }
}
