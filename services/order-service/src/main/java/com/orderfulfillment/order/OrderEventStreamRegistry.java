package com.orderfulfillment.order;

import com.orderfulfillment.order.dto.OrderStatusChangedMessage;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registry of currently-connected {@code GET /api/orders/stream} clients, and the fan-out point for
 * {@link OrderStatusChangedEvent}. docs/openapi/order-service.yaml's {@code orderId} query
 * parameter is a per-connection filter, not a per-topic subscription — there is no Kafka-style
 * partitioning here, so the simplest correct thing is to broadcast every transition to every
 * connected emitter and let each connection's own {@code orderId} filter (recorded at
 * {@link #register}) decide whether to forward it. Documented judgment call: with the handful of
 * concurrent demo viewers this project expects, broadcasting is simpler and no less correct than
 * maintaining a per-order subscriber index, and it's what {@code OrderStatusStreamListener}'s
 * Javadoc and the phase-5 report both point back to.
 *
 * <p>A plain {@link ConcurrentHashMap} of emitters — no need for anything fancier, per this
 * workstream's brief. Emitters remove themselves from the map on completion, timeout, and error, so
 * a client that disconnects (tab closed, network drop) is pruned without operator intervention.
 *
 * <p><b>Per-emitter send synchronization.</b> {@link SseEmitter#send} is not safe to call
 * concurrently from multiple threads on the same emitter instance (Spring's own Javadoc calls this
 * out) — a single connection can legitimately be written to from several different threads at once
 * here: any of Order Service's Kafka listener container threads (inventory/payment/fulfillment
 * events each run on their own thread) can call {@link #broadcast} for an unfiltered connection at
 * roughly the same moment, and the keep-alive {@link #keepAliveExecutor} tick runs on yet another,
 * independent thread on a fixed schedule regardless of what {@link #broadcast} is doing. Without
 * synchronization, two threads' calls to the same {@code SseEmitter}'s underlying writer can
 * interleave mid-write and corrupt the SSE byte stream — observed as a client-side parser
 * reconstructing a garbled or duplicated event. Every send to a given emitter (broadcast and
 * keep-alive alike) therefore synchronizes on that emitter instance, which serializes writes to one
 * connection without blocking writes to any other connection.
 */
@Component
class OrderEventStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(OrderEventStreamRegistry.class);

    /** Generous but bounded: EventSource reconnects automatically, so a periodic forced reconnect
     * is harmless and keeps a stuck/half-open TCP connection from pinning an emitter forever. */
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(15);

    /** {@code ""} means "no filter, every order" — {@link ConcurrentHashMap} cannot hold a null
     * value, and {@code null} is exactly what an unfiltered connection's {@code orderId} query
     * parameter resolves to. */
    private static final String NO_FILTER = "";

    private final Map<SseEmitter, String> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAliveExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "order-stream-keep-alive");
                thread.setDaemon(true);
                return thread;
            });

    OrderEventStreamRegistry() {
        keepAliveExecutor.scheduleAtFixedRate(this::sendKeepAlive,
                KEEP_ALIVE_INTERVAL.toSeconds(), KEEP_ALIVE_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * @param orderIdFilter restrict this connection to one order's transitions, or {@code null} for
     *                       every order's transitions (the query parameter's documented default)
     */
    SseEmitter register(String orderIdFilter) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.put(emitter, orderIdFilter != null ? orderIdFilter : NO_FILTER);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(emitter);
        });
        emitter.onError(ex -> emitters.remove(emitter));
        return emitter;
    }

    void broadcast(OrderStatusChangedEvent event) {
        if (emitters.isEmpty()) {
            return;
        }
        OrderStatusChangedMessage message = new OrderStatusChangedMessage(
                event.orderId(),
                event.status().name(),
                event.previousStatus() != null ? event.previousStatus().name() : null,
                event.sourceEventId(),
                event.correlationId(),
                event.occurredAt());

        emitters.forEach((emitter, orderIdFilter) -> {
            if (!orderIdFilter.isEmpty() && !orderIdFilter.equals(event.orderId())) {
                return;
            }
            // See the class Javadoc: this emitter can also be written to concurrently by the
            // keep-alive tick (or, in principle, another broadcast racing in from a different Kafka
            // listener thread), so the send must be serialized per-emitter.
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().name("order-status-changed").data(message));
                } catch (IOException | IllegalStateException ex) {
                    // Client is gone (broken pipe) or the emitter already completed concurrently —
                    // either way there is nothing left to deliver to; the emitter's own
                    // onError/onCompletion callback removes it from the map.
                    log.debug("Dropping SSE emitter for order stream after send failure", ex);
                    emitter.completeWithError(ex);
                    emitters.remove(emitter);
                }
            }
        });
    }

    private void sendKeepAlive() {
        emitters.forEach((emitter, orderIdFilter) -> {
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException ex) {
                    emitter.completeWithError(ex);
                    emitters.remove(emitter);
                }
            }
        });
    }

    @PreDestroy
    void shutdown() {
        keepAliveExecutor.shutdownNow();
        emitters.keySet().forEach(SseEmitter::complete);
        emitters.clear();
    }
}
