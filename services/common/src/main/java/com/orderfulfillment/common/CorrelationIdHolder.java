package com.orderfulfillment.common;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * Request-scoped correlation id, set by {@link CorrelationIdFilter}. Every event envelope in
 * docs/events/event-catalog.md carries a correlationId constant for one logical workflow; even
 * without Kafka this phase, request logs and the ApiError envelope carry the same id
 * (docs/planning/agent-guidance.md rule 17).
 */
public final class CorrelationIdHolder {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private CorrelationIdHolder() {
    }

    public static void set(UUID correlationId) {
        CURRENT.set(correlationId);
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs {@code action} with {@code correlationId} bound to this thread and to the SLF4J MDC,
     * clearing both afterward — the Kafka-consumer-thread counterpart to what
     * {@link CorrelationIdFilter} does per HTTP request. Every {@code @KafkaListener} method should
     * wrap its processing in this so that {@code EventPublisher} (which reads the holder rather than
     * taking an explicit parameter) and log lines both pick up the correlationId carried on the
     * envelope being consumed.
     */
    public static void runInScope(UUID correlationId, Runnable action) {
        set(correlationId);
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId.toString());
        try {
            action.run();
        } finally {
            clear();
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
