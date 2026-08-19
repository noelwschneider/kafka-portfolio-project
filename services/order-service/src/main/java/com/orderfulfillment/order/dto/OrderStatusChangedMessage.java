package com.orderfulfillment.order.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The JSON body of one {@code order-status-changed} SSE event, per
 * docs/openapi/order-service.yaml's {@code GET /api/orders/stream} description. The per-message
 * schema is explicitly not frozen there, so this shape was chosen to mirror
 * {@link OrderStatusHistoryEntryDto} (already the frozen field names/casing for a status/
 * sourceEventId/occurredAt triple) plus the three extra fields the SSE contract calls out by name:
 * {@code orderId} (the stream can carry every order's transitions, so each message must self-
 * identify), {@code previousStatus} (null only for the very first transition, PENDING), and
 * {@code correlationId}.
 */
public record OrderStatusChangedMessage(
        String orderId,
        String status,
        String previousStatus,
        UUID sourceEventId,
        UUID correlationId,
        Instant occurredAt) {
}
