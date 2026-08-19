package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.scenario.clients.OrderServiceClient;
import org.springframework.stereotype.Component;

/** docs/scenarios.md Scenario 1 — Standard Fulfillment. */
@Component
public class StandardOrderScenario extends AbstractScenarioRunner {

    public StandardOrderScenario(ScenarioToolkit toolkit) {
        super(toolkit);
    }

    @Override
    public String scenarioName() {
        return "standard-order";
    }

    @Override
    public void run(ScenarioRunContext ctx) {
        recordHttp(ctx.runId(), "PUT /demo/payment-behavior", paymentServiceClient.setBehavior("DEFAULT_SUCCESS"));

        OrderServiceClient.OrderCreationResult order =
                createOrder(ctx.runId(), "SKU-001", 2, "demo-customer");
        ctx.setPrimaryOrderId().accept(order.orderId());

        orderStatusWatcher.awaitTerminal(ctx.runId(), order.orderId());
    }
}
