package com.orderfulfillment.scenario.clients;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Wraps Payment Service's {@code /demo/payment-behavior} (docs/openapi/payment-service.yaml). */
@Component
public class PaymentServiceClient {

    private final RestClient client;

    public PaymentServiceClient(RestClient paymentServiceRestClient) {
        this.client = paymentServiceRestClient;
    }

    public int setBehavior(String mode) {
        return client.put().uri("/demo/payment-behavior").body(Map.of("mode", mode))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    public int clearBehavior() {
        return client.delete().uri("/demo/payment-behavior")
                .retrieve().toBodilessEntity().getStatusCode().value();
    }
}
