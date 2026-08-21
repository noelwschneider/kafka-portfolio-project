package com.orderfulfillment.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The background half of ADR-006's transactional outbox for Inventory Service: polls
 * {@code outbox_events} for pending rows and hands each batch to {@link OutboxDispatcher}. Same
 * shape as Order Service's {@code OutboxPublisher} — pure polling, no notify-on-commit hook,
 * {@code fixedDelay} so ticks cannot stack up behind a slow batch.
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxDispatcher dispatcher;

    OutboxPublisher(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${orderfulfillment.outbox.poll-interval-ms:50}")
    void publishPending() {
        try {
            int published = dispatcher.publishPendingBatch();
            if (published > 0) {
                log.debug("Outbox published {} event(s)", published);
            }
        } catch (Exception ex) {
            log.warn("Outbox poll failed; retrying on the next tick", ex);
        }
    }
}
