package com.orderfulfillment.scenario.runtime;

import com.orderfulfillment.scenario.clients.OrderServiceClient;
import com.orderfulfillment.scenario.config.ScenarioProperties;
import com.orderfulfillment.scenario.domain.TimelineKind;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Watches one order's status by polling {@code GET /api/orders/{id}} and appends a STATE_CHANGE
 * timeline entry each time the observed status changes, until a terminal status is reached or the
 * timeout elapses.
 *
 * <p>Order Service's {@code GET /api/orders/stream} SSE endpoint (being built concurrently by a
 * sibling agent per this task's brief) is the preferred source and was not confirmed available when
 * this service was built, so polling is used instead — a documented follow-up, not a silent gap
 * (see the Phase 5 report).
 */
@Component
public class OrderStatusWatcher {

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("REJECTED_OUT_OF_STOCK", "PAYMENT_FAILED", "FULFILLED", "FAILED");

    private final OrderServiceClient orderServiceClient;
    private final TimelineRecorder timelineRecorder;
    private final ScenarioProperties properties;

    public OrderStatusWatcher(OrderServiceClient orderServiceClient, TimelineRecorder timelineRecorder,
                               ScenarioProperties properties) {
        this.orderServiceClient = orderServiceClient;
        this.timelineRecorder = timelineRecorder;
        this.properties = properties;
    }

    /** Blocks the calling (scenario-executor) thread until the order reaches a terminal status or times out. */
    public String awaitTerminal(String runId, String orderId) {
        Set<String> seen = new LinkedHashSet<>();
        long deadline = System.currentTimeMillis() + properties.orderPollTimeoutMs();
        String lastStatus = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> order = orderServiceClient.getOrder(orderId);
            String status = order == null ? null : String.valueOf(order.get("status"));
            if (status != null && seen.add(status)) {
                lastStatus = status;
                timelineRecorder.append(runId, TimelineKind.STATE_CHANGE, "Order " + status,
                        Map.of("orderId", orderId, "status", status));
                if (TERMINAL_STATUSES.contains(status)) {
                    return status;
                }
            }
            sleep();
        }
        return lastStatus;
    }

    private void sleep() {
        try {
            Thread.sleep(properties.orderPollIntervalMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling order status", e);
        }
    }
}
