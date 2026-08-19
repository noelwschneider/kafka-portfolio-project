package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.scenario.clients.OrderServiceClient;
import org.springframework.stereotype.Component;

/** docs/scenarios.md Scenario 2 — Out of Stock. SKU-004 has 2 seeded units; this requests 5. */
@Component
public class OutOfStockScenario extends AbstractScenarioRunner {

    public OutOfStockScenario(ScenarioToolkit toolkit) {
        super(toolkit);
    }

    @Override
    public String scenarioName() {
        return "out-of-stock";
    }

    @Override
    public void run(ScenarioRunContext ctx) {
        OrderServiceClient.OrderCreationResult order =
                createOrder(ctx.runId(), "SKU-004", 5, "demo-customer");
        ctx.setPrimaryOrderId().accept(order.orderId());

        orderStatusWatcher.awaitTerminal(ctx.runId(), order.orderId());
    }
}
