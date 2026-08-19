package com.orderfulfillment.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The background half of ADR-006: polls {@code outbox_events} for pending rows and hands each batch
 * to {@link OutboxDispatcher}. Pure polling, no notify-on-commit hook — ADR-006 offers that as an
 * optional latency optimization, and at the default 50 ms interval the added publication latency is
 * already inside the "tens of milliseconds" the ADR budgets for, which does not justify a second
 * concurrent path into the dispatcher (see docs/agent-reports/phase-6-outbox.md).
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: ticks must not stack up behind a slow batch, since
 * two dispatchers running at once would contend on the same rows for no gain.
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
            // A scheduled method that throws is simply logged by Spring and retried next tick; this
            // catch exists only to keep the message specific (e.g. the database being unreachable,
            // which is not the dispatcher's own per-row failure path).
            log.warn("Outbox poll failed; retrying on the next tick", ex);
        }
    }
}
