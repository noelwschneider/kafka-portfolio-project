package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.InventoryReservedPayload;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.events.ShipmentCreatedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.order.dto.CreateOrderItem;
import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import com.orderfulfillment.order.dto.OrderDetail;
import com.orderfulfillment.order.dto.OrderStatusChangedMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves docs/openapi/order-service.yaml's {@code GET /api/orders/stream} end to end against the
 * real running service: opens a real HTTP connection with a plain {@link HttpClient} (not a mock —
 * a client-side stub could not tell a real long-lived stream from a request/response pair), drives
 * a real happy-path order exactly as {@link OrderServiceIntegrationTest} does, and asserts the
 * {@code order-status-changed} SSE events arrive in the same sequence as the real
 * {@code order_status_history} rows this test's sibling already proves are written.
 *
 * <p>No mocking of the transition mechanism: the same {@link com.orderfulfillment.common.kafka.EventPublisher}
 * bean publishes the upstream events Inventory/Payment/Fulfillment Service would have published, and
 * {@link OrderPersistence}'s real {@code @Transactional(REQUIRES_NEW)} methods, real
 * {@link OrderStatusStreamListener}, and real {@link OrderEventStreamRegistry} do the rest.
 */
class OrderStreamIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void streamPathIsNotShadowedByOrderIdTemplate() throws Exception {
        // BodyHandlers.discarding()/ofString() etc. all block send() until the body completes, which
        // never happens on a live SSE connection — ofInputStream() is the one handler that returns as
        // soon as headers arrive, leaving the (never-ending) body for the caller to read lazily. We
        // only care about the response line here, so the stream is closed immediately after.
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/stream"))
                .GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try {
            // A 404 here would mean "stream" fell through to getOrder(orderId="stream") instead of the
            // dedicated stream endpoint — exactly the routing regression the OpenAPI note warns about.
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(contentType -> assertThat(contentType).startsWith("text/event-stream"));
        } finally {
            response.body().close();
        }
    }

    @Test
    void streamEmitsRealTransitionsInCommitOrder() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/stream"))
                .GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);

        List<OrderStatusChangedMessage> received = new CopyOnWriteArrayList<>();
        Thread reader = startReader(response.body(), received);
        try {
            OrderAccepted accepted = createOrder("SKU-001", 1);

            publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, accepted.id(),
                    new InventoryReservedPayload(accepted.id(), "resv-stream-1",
                            List.of(new EventItem("SKU-001", 1)), Instant.now()));

            // InventoryReserved and PaymentAuthorized are consumed by two independent Kafka listener
            // container threads (inventory.events vs payments.events), so publishing PaymentAuthorized
            // immediately after InventoryReserved races them against each other with no ordering
            // guarantee: in the real system PaymentAuthorized is only ever published once Payment
            // Service has reacted to the PaymentRequested that Order Service itself publishes after
            // InventoryReserved commits, so this reordering cannot happen there. Here, with no
            // upstream causal chain, only this await enforces it — omitting it let the payment-events
            // thread occasionally win the race under full-suite load, writing PAID/FULFILLMENT_PENDING
            // with previousStatus="PENDING" (OrderPersistence.writeStatus has no state-machine guard;
            // it just records whatever the order's current status happens to be). Same category of bug
            // as the ShipmentCreated race fixed below — see docs/agent-reports/phase-5-backend-prep.md §3.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(getOrder(accepted.id()).status()).isEqualTo("PAYMENT_PENDING"));

            publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_AUTHORIZED, accepted.id(),
                    new PaymentAuthorizedPayload(accepted.id(), "pay-stream-1", new BigDecimal("189.00"), Instant.now()));

            // Mirrors OrderServiceIntegrationTest: in the real system ShipmentCreated is only ever
            // published once Fulfillment Service has reacted to PaymentAuthorized, so publishing it
            // here before that transition has actually landed would be racing our own simulated
            // upstream against itself, not proving anything about the stream.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(getOrder(accepted.id()).status()).isEqualTo("FULFILLMENT_PENDING"));

            publish(KafkaTopics.FULFILLMENT_EVENTS, EventTypes.SHIPMENT_CREATED, accepted.id(),
                    new ShipmentCreatedPayload(accepted.id(), "shp-stream-1", "TRACK-STREAM-1", Instant.now()));

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                List<OrderStatusChangedMessage> forThisOrder = received.stream()
                        .filter(m -> m.orderId().equals(accepted.id())).toList();
                assertThat(forThisOrder).extracting(OrderStatusChangedMessage::status)
                        .containsExactly("PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING",
                                "PAID", "FULFILLMENT_PENDING", "FULFILLED");
            });

            List<OrderStatusChangedMessage> forThisOrder = received.stream()
                    .filter(m -> m.orderId().equals(accepted.id())).toList();

            // previousStatus chains correctly, including across the two internal transitions that
            // ride along in the same local transaction as an inbound event (order-state-machine.md).
            assertThat(forThisOrder.get(0).previousStatus()).isNull();
            assertThat(forThisOrder.get(1).previousStatus()).isEqualTo("PENDING");
            assertThat(forThisOrder.get(2).previousStatus()).isEqualTo("INVENTORY_RESERVED");
            assertThat(forThisOrder.get(3).previousStatus()).isEqualTo("PAYMENT_PENDING");
            assertThat(forThisOrder.get(4).previousStatus()).isEqualTo("PAID");
            assertThat(forThisOrder.get(5).previousStatus()).isEqualTo("FULFILLMENT_PENDING");

            // sourceEventId is only populated on the transition an inbound event actually caused, not
            // on the internal transitions riding along with it.
            assertThat(forThisOrder.get(0).sourceEventId()).isNull(); // PENDING: no causing event
            assertThat(forThisOrder.get(1).sourceEventId()).isNotNull(); // INVENTORY_RESERVED
            assertThat(forThisOrder.get(2).sourceEventId()).isNull(); // PAYMENT_PENDING: internal
            assertThat(forThisOrder.get(3).sourceEventId()).isNotNull(); // PAID
            assertThat(forThisOrder.get(4).sourceEventId()).isNull(); // FULFILLMENT_PENDING: internal
            assertThat(forThisOrder.get(5).sourceEventId()).isNotNull(); // FULFILLED

            assertThat(forThisOrder).allSatisfy(m -> assertThat(m.correlationId()).isNotNull());
        } finally {
            // Thread.interrupt() alone does not unblock a thread parked in a blocking read() on a
            // plain socket InputStream, so without closing the stream this connection (and its
            // server-side SseEmitter) would stay registered for the rest of the suite run — one more
            // concurrently-live emitter for every later test's broadcasts/keep-alive ticks to race
            // against. Closing here is what actually makes OrderEventStreamRegistry notice the client
            // is gone and prune the emitter.
            response.body().close();
            reader.interrupt();
        }
    }

    @Test
    void orderIdFilterExcludesOtherOrdersTransitions() throws Exception {
        OrderAccepted watched = createOrder("SKU-001", 1);
        OrderAccepted other = createOrder("SKU-002", 1);

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/orders/stream?orderId=" + watched.id()))
                .GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);

        List<OrderStatusChangedMessage> received = new CopyOnWriteArrayList<>();
        Thread reader = startReader(response.body(), received);
        try {
            publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, other.id(),
                    new InventoryReservedPayload(other.id(), "resv-stream-2",
                            List.of(new EventItem("SKU-002", 1)), Instant.now()));
            publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, watched.id(),
                    new InventoryReservedPayload(watched.id(), "resv-stream-3",
                            List.of(new EventItem("SKU-001", 1)), Instant.now()));

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(received).extracting(OrderStatusChangedMessage::orderId)
                            .contains(watched.id()));

            // Give the (excluded) other order's event a moment to have arrived too, if it were going to.
            Thread.sleep(500);
            assertThat(received).extracting(OrderStatusChangedMessage::orderId)
                    .doesNotContain(other.id());
        } finally {
            // See streamEmitsRealTransitionsInCommitOrder's finally block for why the stream itself,
            // not just the reader thread, must be closed here.
            response.body().close();
            reader.interrupt();
        }
    }

    private Thread startReader(InputStream body, List<OrderStatusChangedMessage> sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                StringBuilder dataBuffer = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        dataBuffer.append(line.substring("data:".length()).trim());
                    } else if (line.isEmpty() && !dataBuffer.isEmpty()) {
                        String json = dataBuffer.toString();
                        dataBuffer.setLength(0);
                        sink.add(objectMapper.readValue(json, OrderStatusChangedMessage.class));
                    }
                }
            } catch (IOException ignored) {
                // stream closed at test teardown/reader.interrupt()
            }
        }, "sse-test-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
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

    private OrderDetail getOrder(String orderId) {
        return client.get().uri("/api/orders/" + orderId).exchange()
                .expectBody(OrderDetail.class).returnResult().getResponseBody();
    }

    private void publish(String topic, String eventType, String aggregateId, Object payload) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(),
                () -> eventPublisher.publish(topic, eventType, aggregateId, payload));
    }
}
