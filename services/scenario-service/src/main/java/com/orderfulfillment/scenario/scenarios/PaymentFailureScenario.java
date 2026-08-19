package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.scenario.clients.OrderServiceClient;
import org.springframework.stereotype.Component;

/**
 * docs/scenarios.md Scenario 3 — Payment Rejection. The `REJECT` override is armed before the order
 * exists and cleared once the run finishes (in a finally block), exactly as the OpenAPI doc's
 * "How a run is composed" section requires — the override is un-scoped for the duration of the run,
 * a deliberate, documented tradeoff (docs/scenarios.md Scenario 3's "Behavior" note).
 */
@Component
public class PaymentFailureScenario extends AbstractScenarioRunner {

    public PaymentFailureScenario(ScenarioToolkit toolkit) {
        super(toolkit);
    }

    @Override
    public String scenarioName() {
        return "payment-failure";
    }

    @Override
    public void run(ScenarioRunContext ctx) {
        recordHttp(ctx.runId(), "PUT /demo/payment-behavior", paymentServiceClient.setBehavior("REJECT"));
        try {
            OrderServiceClient.OrderCreationResult order =
                    createOrder(ctx.runId(), "SKU-001", 1, "demo-customer");
            ctx.setPrimaryOrderId().accept(order.orderId());

            orderStatusWatcher.awaitTerminal(ctx.runId(), order.orderId());
        } finally {
            recordHttp(ctx.runId(), "DELETE /demo/payment-behavior", paymentServiceClient.clearBehavior());
        }
    }
}
