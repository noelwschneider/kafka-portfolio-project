package com.orderfulfillment.order;

import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import com.orderfulfillment.order.dto.OrderDetail;
import com.orderfulfillment.order.dto.OrderPage;
import jakarta.validation.Valid;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** docs/openapi/order-service.yaml's /api namespace — the only service that accepts order creation. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final OrderEventStreamRegistry eventStreamRegistry;

    public OrderController(OrderService orderService, OrderEventStreamRegistry eventStreamRegistry) {
        this.orderService = orderService;
        this.eventStreamRegistry = eventStreamRegistry;
    }

    @PostMapping
    public ResponseEntity<OrderAccepted> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        // Phase 9: this is the first hop of every order workflow, and CorrelationIdFilter has
        // already put the request's correlationId (incoming X-Correlation-Id header, or a
        // freshly minted one) into MDC by the time this method runs — logged here so a
        // scenario's trace has a starting point, not just the Kafka-side hops.
        OrderAccepted accepted = orderService.createOrder(request);
        log.info("Order {} created", accepted.id());
        return ResponseEntity.created(URI.create("/api/orders/" + accepted.id())).body(accepted);
    }

    @GetMapping
    public OrderPage listOrders(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String customerId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return orderService.listOrders(status, customerId, page, size);
    }

    /**
     * docs/openapi/order-service.yaml's {@code GET /api/orders/stream}. Declared ahead of
     * {@link #getOrder} as a matter of readability, matching the OpenAPI note that {@code stream}
     * is a reserved order id — Spring itself resolves literal path segments ahead of
     * {@code {orderId}} regardless of declaration order, so this method being first is not what
     * makes the routing correct, but it documents the intent at the point a future edit could
     * accidentally break it (see {@code OrderStreamEndpointRoutingTest}).
     */
    @GetMapping(path = "/stream", produces = "text/event-stream")
    public SseEmitter streamOrderEvents(@RequestParam(required = false) String orderId) {
        return eventStreamRegistry.register(orderId);
    }

    @GetMapping("/{orderId}")
    public OrderDetail getOrder(@PathVariable String orderId) {
        return orderService.getOrder(orderId);
    }
}
