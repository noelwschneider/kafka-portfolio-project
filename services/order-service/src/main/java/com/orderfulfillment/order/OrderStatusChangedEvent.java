package com.orderfulfillment.order;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@link OrderPersistence} immediately after each real {@code order_status_history}
 * write, from inside the same {@code @Transactional(REQUIRES_NEW)} method that made it. This is a
 * plain Spring application event (Spring's {@code ApplicationEventPublisher} accepts arbitrary
 * objects, not just {@code ApplicationEvent} subclasses) rather than a Kafka record — it exists
 * only to let {@link OrderStatusStreamListener} learn about a transition after its transaction
 * commits, per docs/openapi/order-service.yaml's {@code GET /api/orders/stream} contract.
 *
 * @param orderId        the order whose status changed
 * @param status         the new status
 * @param previousStatus the status before this transition, or {@code null} for the very first
 *                       transition ({@code PENDING}, written by {@code createPendingOrder})
 * @param sourceEventId  the {@code eventId} of the inbound Kafka event that caused this transition,
 *                       or {@code null} when the transition was not caused by one (the initial
 *                       {@code PENDING} write, and the internal transitions
 *                       {@code PAYMENT_PENDING}/{@code FULFILLMENT_PENDING} that ride along with
 *                       another transition in the same local transaction — see
 *                       docs/order-state-machine.md's "Notes on the internal transitions")
 * @param correlationId  the correlation id in scope on the thread that made this write —
 *                       {@link com.orderfulfillment.common.CorrelationIdHolder#get()}, bound either
 *                       by {@code CorrelationIdFilter} (HTTP) or {@code CorrelationIdHolder.runInScope}
 *                       (Kafka listener threads); {@code REQUIRES_NEW} keeps the same thread, so the
 *                       value is still in scope inside these transactional methods
 * @param occurredAt     when the write happened (matches the {@code order_status_history} row)
 */
record OrderStatusChangedEvent(
        String orderId,
        OrderStatus status,
        OrderStatus previousStatus,
        UUID sourceEventId,
        UUID correlationId,
        Instant occurredAt) {
}
