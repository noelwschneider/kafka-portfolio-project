package com.orderfulfillment.order;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.events.PaymentRequestedPayload;
import com.orderfulfillment.common.idempotency.ProcessedEventKey;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import com.orderfulfillment.common.kafka.EventTypes;
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
 *
 * <p>Since Phase 6 these transactions also carry the outbound event itself: the two methods that
 * produce one ({@link #createPendingOrder} → OrderCreated, {@link #appendInventoryReservedTransition}
 * → PaymentRequested) insert it into {@code outbox_events} via {@link OutboxRecorder} rather than
 * letting their caller publish to Kafka after the commit. Business row, ledger claim and outbound
 * event are now one atomic unit; {@link OutboxPublisher} does the actual sending
 * (docs/adr/ADR-006-transactional-outbox-for-db-kafka-consistency.md).
 */
@Component
class OrderPersistence {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final ProcessedEventLedger processedEventLedger;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxRecorder outboxRecorder;

    OrderPersistence(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                      OrderStatusHistoryRepository historyRepository, ProcessedEventLedger processedEventLedger,
                      ApplicationEventPublisher eventPublisher, OutboxRecorder outboxRecorder) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.historyRepository = historyRepository;
        this.processedEventLedger = processedEventLedger;
        this.eventPublisher = eventPublisher;
        this.outboxRecorder = outboxRecorder;
    }

    /**
     * Writes the order and records OrderCreated in the outbox, in one transaction (ADR-006). The
     * event is built here rather than by the caller precisely so it cannot be forgotten: there is
     * no longer any code path that creates an order without also committing its event.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OrderEntity createPendingOrder(String orderId, String customerId, List<OrderItemEntity> items, BigDecimal totalAmount) {
        Instant now = Instant.now();
        OrderEntity order = new OrderEntity(orderId, customerId, OrderStatus.PENDING, totalAmount, now);
        orderRepository.save(order);
        orderItemRepository.saveAll(items);
        historyRepository.save(new OrderStatusHistoryEntity(orderId, OrderStatus.PENDING, null, now));
        publishStatusChanged(orderId, OrderStatus.PENDING, null, null, now);

        List<EventItem> eventItems = items.stream()
                .map(item -> new EventItem(item.getSku(), item.getQuantity()))
                .toList();
        outboxRecorder.record(EventTypes.ORDER_CREATED, orderId,
                new OrderCreatedPayload(orderId, customerId, eventItems));
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
     * transaction, internal transition 4 ({@code PAYMENT_PENDING}) — docs/order-state-machine.md —
     * and records the {@code PaymentRequested} that follows from it in the outbox.
     *
     * <p>That last part is a Phase 6 addition beyond ADR-006's prose, which scoped the outbox to
     * "the publisher whose lost event strands an order" and assumed this publish site was covered by
     * redelivery. It is not: the {@code processed_events} claim above commits <em>with</em> these
     * status writes, so a crash after this commit but before a post-commit publish would leave a
     * redelivered InventoryReserved to be discarded as a duplicate by
     * {@link OrderInventoryEventsConsumer}, stranding the order at PAYMENT_PENDING exactly as a lost
     * OrderCreated strands it at PENDING. See docs/agent-reports/phase-6-outbox.md.
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

        UUID paymentRequestedEventId = UUID.randomUUID();
        outboxRecorder.record(EventTypes.PAYMENT_REQUESTED, orderId, paymentRequestedEventId,
                new PaymentRequestedPayload(orderId, order.getTotalAmount(), paymentRequestedEventId));
        return StatusTransitionResult.asApplied();
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
