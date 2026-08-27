package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.scenario.clients.OrderServiceClient;
import org.springframework.stereotype.Component;

/** docs/scenarios.md Scenario 1 — Standard Fulfillment. */
@Component
public class StandardOrderScenario extends AbstractScenarioRunner {

    /** Names the order this scenario creates so it reads unmistakably on the Orders page. */
    private static final String CUSTOMER_NAME = "Sam Standard";

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
                createOrder(ctx.runId(), "SKU-001", 2, CUSTOMER_NAME);
        ctx.setPrimaryOrderId().accept(order.orderId());

        orderStatusWatcher.awaitTerminal(ctx.runId(), order.orderId());
    }
}
