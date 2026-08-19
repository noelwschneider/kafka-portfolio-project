package com.orderfulfillment.scenario.clients;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Wraps Order Service's public {@code /api/orders} — the only endpoint a scenario uses to create an order. */
@Component
public class OrderServiceClient {

    private final RestClient client;

    public OrderServiceClient(RestClient orderServiceRestClient) {
        this.client = orderServiceRestClient;
    }

    public record OrderCreationResult(int statusCode, String orderId, String status) {
    }

    public OrderCreationResult createOrder(String customerId, List<Map<String, Object>> items) {
        Map<String, Object> body = Map.of("customerId", customerId, "items", items);
        ResponseEntity<Map<String, Object>> response = client.post()
                .uri("/api/orders")
                .body(body)
                .retrieve()
                .toEntity(new org.springframework.core.ParameterizedTypeReference<>() {
                });
        Map<String, Object> payload = response.getBody();
        String orderId = payload == null ? null : String.valueOf(payload.get("id"));
        String status = payload == null ? null : String.valueOf(payload.get("status"));
        return new OrderCreationResult(response.getStatusCode().value(), orderId, status);
    }

    public Map<String, Object> getOrder(String orderId) {
        return client.get()
                .uri("/api/orders/{id}", orderId)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });
    }
}
