package com.orderfulfillment.order;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges {@link OrderStatusChangedEvent} to {@link OrderEventStreamRegistry}, and is the whole
 * reason the event exists as a separate step rather than pushing to SSE directly from
 * {@link OrderPersistence}: {@code @TransactionalEventListener(phase = AFTER_COMMIT)} defers
 * delivery until the publishing method's {@code @Transactional(REQUIRES_NEW)} transaction actually
 * commits. A subscriber therefore never sees a status the database doesn't durably have yet (the
 * event fires strictly after the {@code order_status_history} row is committed), and never sees a
 * status for a transition that later rolled back (a rollback means the event is silently dropped —
 * Spring never invokes an AFTER_COMMIT listener for a transaction that didn't commit). This is the
 * "real transitions only, never a poll dressed up as a stream" requirement from
 * docs/openapi/order-service.yaml, made structural rather than a convention someone could forget.
 */
@Component
class OrderStatusStreamListener {

    private final OrderEventStreamRegistry registry;

    OrderStatusStreamListener(OrderEventStreamRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onOrderStatusChanged(OrderStatusChangedEvent event) {
        registry.broadcast(event);
    }
}
