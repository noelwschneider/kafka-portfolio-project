package com.orderfulfillment.monolith.common;

import java.util.UUID;

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
}
