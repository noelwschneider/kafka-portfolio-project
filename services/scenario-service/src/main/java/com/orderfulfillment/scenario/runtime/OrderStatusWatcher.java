package com.orderfulfillment.scenario.runtime;

import com.orderfulfillment.common.CorrelationIdFilter;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.scenario.clients.OrderServiceClient;
import com.orderfulfillment.scenario.config.ScenarioProperties;
import com.orderfulfillment.scenario.config.ServiceUrlsProperties;
import com.orderfulfillment.scenario.domain.TimelineKind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Watches one order's status until it reaches a terminal status or the timeout elapses, appending a
 * STATE_CHANGE timeline entry each time the observed status changes.
 *
 * <p>Primary path is Order Service's {@code GET /api/orders/stream} SSE endpoint — this was the
 * preferred source all along, but not confirmed available when this service was first built, so a
 * poll loop against {@code GET /api/orders/{id}} was used as the only mechanism (documented follow-up
 * in the Phase 5 report). Now that the stream exists, it is tried first; the poll loop remains as a
 * fallback for exactly two cases: the stream never connects (non-200, e.g. a test's WireMock stub
 * that only scripts the polling endpoint — see AbstractIntegrationTest — or a real Order Service that
 * doesn't support the endpoint), or it connects but drops/times out before a terminal status arrives.
 * Both paths share one {@code seen} set and one overall deadline so a mid-run fallback doesn't
 * re-emit a timeline entry for a status the stream already recorded.
 */
@Component
public class OrderStatusWatcher {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusWatcher.class);

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("REJECTED_OUT_OF_STOCK", "PAYMENT_FAILED", "FULFILLED", "FAILED");

    /** Generous but bounded: just long enough to distinguish "not supported/unreachable" from a
     * genuinely slow connection, without eating meaningfully into the overall poll timeout budget. */
    private static final Duration SSE_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final OrderServiceClient orderServiceClient;
    private final TimelineRecorder timelineRecorder;
    private final ScenarioProperties properties;
    private final ServiceUrlsProperties serviceUrls;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OrderStatusWatcher(OrderServiceClient orderServiceClient, TimelineRecorder timelineRecorder,
                               ScenarioProperties properties, ServiceUrlsProperties serviceUrls,
                               ObjectMapper objectMapper) {
        this.orderServiceClient = orderServiceClient;
        this.timelineRecorder = timelineRecorder;
        this.properties = properties;
        this.serviceUrls = serviceUrls;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(SSE_CONNECT_TIMEOUT).build();
    }

    /** Blocks the calling (scenario-executor) thread until the order reaches a terminal status or times out. */
    public String awaitTerminal(String runId, String orderId) {
        Set<String> seen = new LinkedHashSet<>();
        long deadline = System.currentTimeMillis() + properties.orderPollTimeoutMs();

        String terminal = awaitViaSse(runId, orderId, seen, deadline);
        if (terminal != null) {
            return terminal;
        }
        return awaitViaPolling(runId, orderId, seen, deadline);
    }

    /** @return the terminal status if reached over the stream, or {@code null} if the stream never
     *          connected, dropped, or the deadline passed first — in which case {@code seen} still
     *          holds whatever statuses were recorded, for the polling fallback to continue from. */
    private String awaitViaSse(String runId, String orderId, Set<String> seen, long deadline) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrls.orderService() + "/api/orders/stream?orderId=" + orderId))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMillis(Math.max(1, deadline - System.currentTimeMillis())))
                .GET();
        UUID correlationId = CorrelationIdHolder.get();
        if (correlationId != null) {
            requestBuilder.header(CorrelationIdFilter.HEADER, correlationId.toString());
        }

        HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException | InterruptedException e) {
            log.debug("Order status stream unreachable for {}, falling back to polling", orderId, e);
            return null;
        }
        if (response.statusCode() != 200) {
            log.debug("Order status stream returned {} for {}, falling back to polling", response.statusCode(), orderId);
            return null;
        }

        String pendingEventName = null;
        Iterator<String> lines = response.body().iterator();
        try {
            while (System.currentTimeMillis() < deadline && lines.hasNext()) {
                String line = lines.next();
                if (line.startsWith("event:")) {
                    pendingEventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:") && "order-status-changed".equals(pendingEventName)) {
                    String terminal = handleStatusMessage(runId, orderId, line.substring("data:".length()).trim(), seen);
                    if (terminal != null) {
                        return terminal;
                    }
                    pendingEventName = null;
                } else if (line.isEmpty()) {
                    pendingEventName = null;
                }
            }
        } catch (RuntimeException e) {
            // The blocking line iterator wraps I/O failures (dropped connection) as unchecked.
            log.debug("Order status stream for {} ended early, falling back to polling", orderId, e);
        }
        return null;
    }

    private String handleStatusMessage(String runId, String orderId, String json, Set<String> seen) {
        Map<String, Object> message;
        try {
            message = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException e) {
            log.debug("Could not parse order-status-changed payload: {}", json, e);
            return null;
        }
        if (!orderId.equals(String.valueOf(message.get("orderId")))) {
            return null;
        }
        String status = String.valueOf(message.get("status"));
        return recordIfNew(runId, orderId, status, seen);
    }

    private String awaitViaPolling(String runId, String orderId, Set<String> seen, long deadline) {
        String lastStatus = seen.isEmpty() ? null : lastElement(seen);
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> order = orderServiceClient.getOrder(orderId);
            String status = order == null ? null : String.valueOf(order.get("status"));
            if (status != null) {
                String terminal = recordIfNew(runId, orderId, status, seen);
                if (terminal != null) {
                    return terminal;
                }
                if (seen.contains(status)) {
                    lastStatus = status;
                }
            }
            sleep();
        }
        return lastStatus;
    }

    /** @return {@code status} if it is both newly-seen and terminal, else {@code null}. */
    private String recordIfNew(String runId, String orderId, String status, Set<String> seen) {
        if (!seen.add(status)) {
            return null;
        }
        timelineRecorder.append(runId, TimelineKind.STATE_CHANGE, "Order " + status,
                Map.of("orderId", orderId, "status", status));
        return TERMINAL_STATUSES.contains(status) ? status : null;
    }

    private String lastElement(Set<String> set) {
        String last = null;
        for (String s : set) {
            last = s;
        }
        return last;
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
