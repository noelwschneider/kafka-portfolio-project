package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.orderfulfillment.order.dto.CreateOrderItem;
import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

/**
 * Sprint 2 goal 2, item 3 — the SSE-under-concurrency defect originally documented in
 * {@code OrderStatusWatcher}'s Javadoc ({@code awaitTerminalPollOnly}), checked against current
 * code rather than assumed fixed.
 *
 * <p>The sprint-2 bug hunt (docs/agent-reports/sprint-2/bug-hunt.md, defect 3) fixed a
 * related-but-distinct problem: a dead SSE emitter's {@code completeWithError} cleanup call itself
 * throwing and leaking out of {@code OrderEventStreamRegistry#broadcast} into an <em>unrelated</em>
 * caller's request thread (e.g. failing someone else's already-committed {@code POST /api/orders}).
 * That fix wraps the {@code completeWithError} call in its own try/catch and is proven by
 * {@code OrderStreamBrokenConnectionIntegrationTest} — which asserts exactly that: other requests
 * keep succeeding.
 *
 * <p>{@code OrderStatusWatcher}'s Javadoc describes a different failure, belonging to the dead
 * connection's <em>own</em> request/response cycle: the servlet container dispatching the async
 * error back through the normal Spring MVC exception-resolution pipeline, where
 * {@code GlobalExceptionHandler}'s catch-all tries to write a JSON {@code ApiError} body onto a
 * response whose {@code Content-Type} is already committed to {@code text/event-stream} — for which
 * no {@code HttpMessageConverter} exists, throwing {@code HttpMessageNotWritableException}. Nothing
 * in the bug-hunt fix touches that path: wrapping {@code completeWithError} in try/catch only
 * catches an exception thrown synchronously from that call itself, not one thrown later by a
 * container-driven async error dispatch the call may trigger. So the two defects are genuinely
 * separate, and {@code OrderStreamBrokenConnectionIntegrationTest} cannot detect this one — it only
 * ever asserts that *other* requests succeed, never that the dead connection's own handling stayed
 * quiet.
 *
 * <p>This test reproduces the same broken-connection scenario and instead inspects whether the
 * server logs anything about a write failure at all, via a Logback {@link ListAppender} attached to
 * the root logger for the duration of the repro — broader than grepping for one exception class
 * name, so it also catches whatever the async error dispatch actually produces if the defect is
 * still present.
 */
class OrderStatusStreamOwnErrorIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aDeadStreamConnectionsOwnErrorDispatchDoesNotSurfaceAWriteFailure() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            Socket socket = new Socket("localhost", port);
            try {
                OutputStream out = socket.getOutputStream();
                String request = "GET /api/orders/stream HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Accept: text/event-stream\r\n"
                        + "Connection: keep-alive\r\n\r\n";
                out.write(request.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                // Give the server a moment to accept the connection and register the emitter.
                Thread.sleep(500);
                // SO_LINGER(0) makes close() send a TCP RST rather than a clean FIN, reproducing a
                // genuinely broken pipe (the same technique OrderStreamBrokenConnectionIntegrationTest
                // uses for the already-fixed defect).
                socket.setSoLinger(true, 0);
            } finally {
                socket.close();
            }

            // Several broadcasts, each a chance for the dead emitter's send to fail and drive its
            // full cleanup/error path, including whatever the servlet container does with the async
            // context afterward.
            for (int i = 0; i < 8; i++) {
                createOrder("SKU-001", 1);
            }

            // The container's own async error dispatch, if it happens at all, is not on the request
            // thread above — give it a moment to actually run and log before inspecting the appender.
            Thread.sleep(2000);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        List<ILoggingEvent> failures = appender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(e -> {
                    String msg = e.getFormattedMessage();
                    String throwableClass = e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getClassName();
                    return (msg != null && (msg.contains("orders/stream") || msg.contains("HttpMessageNotWritable")))
                            || throwableClass.contains("HttpMessageNotWritableException")
                            || throwableClass.contains("AsyncRequestNotUsableException");
                })
                .toList();

        assertThat(failures)
                .as("no WARN/ERROR log should be produced by the dead SSE connection's own error "
                        + "handling once it breaks — see this class's Javadoc for the specific defect "
                        + "this checks for. Logged events found: %s", failures)
                .isEmpty();
    }

    private OrderAccepted createOrder(String sku, int quantity) {
        CreateOrderRequest request = new CreateOrderRequest("demo-customer",
                List.of(new CreateOrderItem(sku, quantity)));
        return client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderAccepted.class)
                .returnResult().getResponseBody();
    }
}
