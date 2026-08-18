package com.orderfulfillment.order;

import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import com.orderfulfillment.order.dto.OrderDetail;
import com.orderfulfillment.order.dto.OrderPage;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** docs/openapi/order-service.yaml's /api namespace — the only service that accepts order creation. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderAccepted> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderAccepted accepted = orderService.createOrder(request);
        return ResponseEntity.created(URI.create("/api/orders/" + accepted.id())).body(accepted);
    }

    @GetMapping
    public OrderPage listOrders(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String customerId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return orderService.listOrders(status, customerId, page, size);
    }

    @GetMapping("/{orderId}")
    public OrderDetail getOrder(@PathVariable String orderId) {
        return orderService.getOrder(orderId);
    }
}
