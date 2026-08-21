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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Since ADR-009 every write also goes through the frozen transition table
 * ({@link OrderTransitions}) rather than trusting the status its caller asked for. Order status is
 * driven by three independently-consumed topics with no ordering guarantee between them, so a
 * transition can arrive before its predecessor (the {@code payments.events} fan-out lets Fulfillment
 * Service publish {@code ShipmentCreated} before Order Service has processed the
 * {@code PaymentAuthorized} that caused it). Such a transition is parked in
 * {@code deferred_transitions} and re-offered after every subsequent status change; a transition the
 * order has already passed — including anything that would move it off a terminal state — is
 * dropped. See docs/adr/ADR-009-out-of-order-status-transitions.md.
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

    private static final Logger log = LoggerFactory.getLogger(OrderPersistence.class);

    /**
     * Safety stop on the drain loop. Applying one parked transition can unblock another, so the loop
     * repeats; this bounds it well above the longest legal chain (six statuses) so a hypothetical
     * cycle cannot spin a transaction forever.
     */
    private static final int MAX_DRAIN_PASSES = 10;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final DeferredTransitionRepository deferredTransitionRepository;
    private final ProcessedEventLedger processedEventLedger;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxRecorder outboxRecorder;

    OrderPersistence(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                      OrderStatusHistoryRepository historyRepository,
                      DeferredTransitionRepository deferredTransitionRepository,
                      ProcessedEventLedger processedEventLedger,
                      ApplicationEventPublisher eventPublisher, OutboxRecorder outboxRecorder) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.historyRepository = historyRepository;
        this.deferredTransitionRepository = deferredTransitionRepository;
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
        OrderEntity order = lockOrder(orderId);
        return switch (OrderTransitions.classify(order.getStatus(), status)) {
            case APPLY -> {
                writeStatus(order, status, sourceEventId);
                drainDeferred(order);
                yield StatusTransitionResult.asApplied();
            }
            case AHEAD -> {
                defer(order, status, sourceEventId);
                yield StatusTransitionResult.asDeferred();
            }
            case STALE -> {
                logStale(order, status, sourceEventId);
                yield StatusTransitionResult.asStale();
            }
        };
    }

    /**
     * Transition 9 (docs/order-state-machine.md) — {@link OrderDeadLetterConsumer} calls this for
     * every record it observes on this service's own {@code orders.dlq}: a non-retryable processing
     * failure, or retries exhausted, for one of this order's inbound events
     * (InventoryReserved/InventoryReservationFailed, PaymentAuthorized/PaymentRejected,
     * ShipmentCreated). Order Service can no longer trust its own view of that order's progress once
     * one of those events could not be applied, so the order moves to the fault terminal state
     * rather than being silently left at whatever status it last reached.
     *
     * <p>No {@code processed_events} claim here (unlike every other transition above): a dead-letter
     * record has no reliable {@code eventId} to key one on — the poison-bytes case that is the most
     * common reason a record reaches the DLQ is exactly the case where the envelope may not parse at
     * all. Idempotency instead comes from {@link OrderTransitions#classify}'s own guard: once the
     * order is FAILED (or has reached any other terminal state), a redelivered or duplicate
     * dead-letter record for the same order classifies as {@code STALE} and writes nothing — the same
     * mechanism ADR-009 already relies on for out-of-order domain events.
     *
     * <p>Tolerates an order that does not exist (an orderId that never became a real order, or one
     * this service has no record of) by logging and doing nothing, rather than throwing — a listener
     * on this service's own terminal failure sink must never itself fail loudly.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    StatusTransitionResult markFailed(String orderId) {
        Optional<OrderEntity> maybeOrder = orderRepository.findByIdForUpdate(orderId);
        if (maybeOrder.isEmpty()) {
            log.warn("Cannot apply the FAILED transition for order {}: no such order exists. The "
                    + "dead-lettered record's aggregateId did not correspond to an order this service "
                    + "created.", orderId);
            return StatusTransitionResult.asStale();
        }
        OrderEntity order = maybeOrder.get();
        return switch (OrderTransitions.classify(order.getStatus(), OrderStatus.FAILED)) {
            case APPLY -> {
                writeStatus(order, OrderStatus.FAILED, null);
                // FAILED is terminal, so any transition still parked for this order (e.g. a
                // FULFILLED waiting on the very PaymentAuthorized that just got dead-lettered) can
                // never apply — drain now so it is marked ABANDONED instead of sitting PENDING
                // forever with nothing left to ever re-offer it (ADR-009).
                drainDeferred(order);
                yield StatusTransitionResult.asApplied();
            }
            case STALE -> {
                logStale(order, OrderStatus.FAILED, null);
                yield StatusTransitionResult.asStale();
            }
            // Unreachable: FAILED's predecessor set (OrderTransitions.VALID_PREDECESSORS) is "any
            // non-terminal status", so classify() always resolves APPLY or STALE for this target —
            // never AHEAD, which only arises for a target with a narrower predecessor set than the
            // order's current progress. Handled anyway so this switch stays exhaustive and safe.
            case AHEAD -> {
                log.error("Unexpected AHEAD verdict marking order {} FAILED from {} — this should be "
                        + "unreachable; leaving the order untouched", orderId, order.getStatus());
                yield StatusTransitionResult.asDeferred();
            }
        };
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
        OrderEntity order = lockOrder(orderId);
        // INVENTORY_RESERVED's only valid predecessor is PENDING, the lowest status there is, so
        // this can only be APPLY or STALE — never AHEAD. classify() is still the authority.
        if (OrderTransitions.classify(order.getStatus(), OrderStatus.INVENTORY_RESERVED)
                != OrderTransitions.Verdict.APPLY) {
            logStale(order, OrderStatus.INVENTORY_RESERVED, sourceEventId);
            return StatusTransitionResult.asStale();
        }
        writeStatus(order, OrderStatus.INVENTORY_RESERVED, sourceEventId);
        writeStatus(order, OrderStatus.PAYMENT_PENDING, null);

        UUID paymentRequestedEventId = UUID.randomUUID();
        outboxRecorder.record(EventTypes.PAYMENT_REQUESTED, orderId, paymentRequestedEventId,
                new PaymentRequestedPayload(orderId, order.getTotalAmount(), paymentRequestedEventId));
        drainDeferred(order);
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
        OrderEntity order = lockOrder(orderId);
        return switch (OrderTransitions.classify(order.getStatus(), OrderStatus.PAID)) {
            case APPLY -> {
                writeStatus(order, OrderStatus.PAID, sourceEventId);
                writeStatus(order, OrderStatus.FULFILLMENT_PENDING, null);
                // The whole point of ADR-009's drain: an early ShipmentCreated parked while this
                // order sat at PAYMENT_PENDING becomes applicable the instant FULFILLMENT_PENDING
                // exists, and is applied here, in this same transaction.
                drainDeferred(order);
                yield StatusTransitionResult.asApplied();
            }
            case AHEAD -> {
                // Not reachable today — PaymentAuthorized answers a PaymentRequested this service
                // only publishes from the PAYMENT_PENDING transition. Handled anyway, and as a pair,
                // because transition 7 is not separately event-driven: deferring only PAID would
                // leave FULFILLMENT_PENDING with nothing to write it.
                defer(order, OrderStatus.PAID, sourceEventId);
                defer(order, OrderStatus.FULFILLMENT_PENDING, null);
                yield StatusTransitionResult.asDeferred();
            }
            case STALE -> {
                // The bug ADR-009 fixes, seen from the other side: this is the late PaymentAuthorized
                // that used to overwrite an already-FULFILLED order back to FULFILLMENT_PENDING.
                logStale(order, OrderStatus.PAID, sourceEventId);
                yield StatusTransitionResult.asStale();
            }
        };
    }

    /**
     * Reads the order under {@code SELECT ... FOR UPDATE}. Every transition below takes this lock
     * before it reads the current status, so the guard cannot be evaluated against a status a
     * concurrent transition is in the middle of changing — see {@link OrderRepository#findByIdForUpdate}.
     */
    private OrderEntity lockOrder(String orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow();
    }

    /**
     * Writes one order_status_history row and moves orders.status — docs/order-state-machine.md §3.
     * Callers must have already taken the row lock and cleared the transition with
     * {@link OrderTransitions#classify}; this method itself no longer decides anything.
     */
    private void writeStatus(OrderEntity order, OrderStatus status, UUID sourceEventId) {
        OrderStatus previousStatus = order.getStatus();
        Instant now = Instant.now();
        order.setStatus(status);
        order.setUpdatedAt(now);
        historyRepository.save(new OrderStatusHistoryEntity(order.getId(), status, sourceEventId, now));
        publishStatusChanged(order.getId(), status, previousStatus, sourceEventId, now);
    }

    /**
     * Parks a transition whose predecessor has not been applied yet. The row commits in the same
     * transaction as this event's {@code processed_events} claim, so the event is exactly as durably
     * accounted for as if it had been applied — nothing is left to Kafka redelivery, which the
     * ledger would suppress anyway (docs/reliability-pattern.md §2.2), and nothing depends on the
     * ~3.5 s infrastructural retry budget of §4.3, which is far shorter than the multi-second races
     * measured in docs/agent-reports/phase-10-scaling-demo.md §4.
     */
    private void defer(OrderEntity order, OrderStatus status, UUID sourceEventId) {
        deferredTransitionRepository.save(
                new DeferredTransitionEntity(order.getId(), status, sourceEventId, Instant.now()));
        log.info("Deferring {} for order {} — arrived before its predecessor; order is at {}",
                status, order.getId(), order.getStatus());
    }

    /**
     * Re-offers this order's parked transitions after a status change, applying every one the
     * transition table now allows. Repeats while progress is being made, because applying one parked
     * transition can unblock another. Anything still parked once the order is terminal can never
     * apply and is marked ABANDONED rather than left to look pending forever.
     */
    private void drainDeferred(OrderEntity order) {
        for (int pass = 0; pass < MAX_DRAIN_PASSES; pass++) {
            boolean appliedAny = false;
            List<DeferredTransitionEntity> parked = deferredTransitionRepository
                    .findByOrderIdAndStatusOrderByIdAsc(order.getId(), DeferredTransitionStatus.PENDING);
            if (parked.isEmpty()) {
                return;
            }
            for (DeferredTransitionEntity deferred : parked) {
                switch (OrderTransitions.classify(order.getStatus(), deferred.getTargetStatus())) {
                    case APPLY -> {
                        log.info("Applying deferred {} for order {} now that it is at {}",
                                deferred.getTargetStatus(), order.getId(), order.getStatus());
                        writeStatus(order, deferred.getTargetStatus(), deferred.getSourceEventId());
                        deferred.resolve(DeferredTransitionStatus.APPLIED, Instant.now());
                        appliedAny = true;
                    }
                    case STALE -> {
                        log.warn("Abandoning deferred {} for order {}: order is at {}, which it can "
                                        + "never follow", deferred.getTargetStatus(), order.getId(),
                                order.getStatus());
                        deferred.resolve(DeferredTransitionStatus.ABANDONED, Instant.now());
                    }
                    case AHEAD -> { /* still waiting on its predecessor — leave it parked */ }
                }
            }
            if (!appliedAny) {
                return;
            }
        }
        log.error("Deferred-transition drain for order {} did not settle in {} passes; leaving the "
                + "remaining rows parked", order.getId(), MAX_DRAIN_PASSES);
    }

    private void logStale(OrderEntity order, OrderStatus status, UUID sourceEventId) {
        log.warn("Ignoring stale transition to {} for order {} (event {}): order is already at {}. "
                        + "docs/order-state-machine.md §3 permits no such transition, and applying it "
                        + "would move the order backwards.",
                status, order.getId(), sourceEventId, order.getStatus());
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
