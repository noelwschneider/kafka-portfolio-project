package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderfulfillment.order.dto.CreateOrderItem;
import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Bug hunt (sprint-2 goal 7): reproduces a real defect found live under a concurrent-SSE-fan-out
 * load test against docker compose. {@code OrderEventStreamRegistry#broadcast} runs synchronously
 * on whatever thread committed the business transaction that produced the event — for order
 * creation, that is the {@code POST /api/orders} request thread itself, via
 * {@link OrderStatusStreamListener}'s {@code @TransactionalEventListener}. When a *different*,
 * already-broken SSE client's socket makes {@code emitter.send} throw, the original code's cleanup
 * path (in the {@code catch} block) called {@code emitter.completeWithError}, which was observed to
 * itself throw a second, uncaught exception once the connection was broken badly enough — and that
 * second exception was not caught by the surrounding {@code catch}, so it propagated out of
 * {@code broadcast()} and failed the unrelated {@code POST /api/orders} call whose transaction had
 * already committed successfully. A client disconnecting from the live status stream must never be
 * able to fail someone else's order-creation request. See docs/agent-reports/sprint-2/bug-hunt.md.
 *
 * <p>This test opens a raw socket SSE connection, forces a TCP reset (not a clean close) so the
 * server-side emitter is in the broken state that reproduced the defect, then creates an order and
 * asserts it still succeeds — proving the broadcast triggered by that commit cannot fail the caller
 * regardless of what happens to the dead connection.
 */
class OrderStreamBrokenConnectionIntegrationTest extends AbstractIntegrationTest {

    @Test
    void brokenSseConnectionDoesNotFailUnrelatedOrderCreation() throws Exception {
        Socket socket = new Socket("localhost", port);
        try {
            OutputStream out = socket.getOutputStream();
            String request = "GET /api/orders/stream HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Accept: text/event-stream\r\n"
                    + "Connection: keep-alive\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Give the server a moment to accept the connection and register the emitter before we
            // yank it out from under it.
            Thread.sleep(500);

            // SO_LINGER(0) makes the subsequent close() send a TCP RST instead of a clean FIN, so the
            // server's next write to this socket fails with a genuine broken-pipe/connection-reset
            // IOException rather than a graceful EOF — this is what a killed curl client under real
            // concurrent load produces, and what the original bug needed to reproduce.
            socket.setSoLinger(true, 0);
        } finally {
            socket.close();
        }

        // The broken connection is still registered server-side until the next broadcast discovers
        // it. Creating several orders gives multiple broadcasts a chance to hit the dead emitter
        // (matching how the live repro needed concurrent traffic, not just one lucky broadcast) and
        // asserts every one of them succeeds regardless.
        for (int i = 0; i < 5; i++) {
            OrderAccepted accepted = createOrder("SKU-001", 1);
            assertThat(accepted.status()).isEqualTo("PENDING");
        }
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
