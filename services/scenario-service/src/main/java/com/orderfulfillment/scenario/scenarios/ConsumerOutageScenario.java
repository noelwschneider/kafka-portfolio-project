package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.scenario.clients.OrderServiceClient;
import com.orderfulfillment.scenario.config.ScenarioProperties;
import org.springframework.stereotype.Component;

/**
 * docs/scenarios.md Scenario 5 — Consumer Outage and Recovery. Pauses Inventory Service's real
 * {@code order-created} Spring Kafka listener container through its own demo control endpoint, creates
 * an order while paused (it durably queues on {@code orders.events} and the order sits {@code PENDING}),
 * then resumes the listener and waits for the backlog to drain to {@code FULFILLED}.
 */
@Component
public class ConsumerOutageScenario extends AbstractScenarioRunner {

    private static final String LISTENER_ID = "order-created";

    /** Names the order this scenario creates so it reads unmistakably on the Orders page. */
    private static final String CUSTOMER_NAME = "Olive Outage";

    private final ScenarioProperties properties;

    public ConsumerOutageScenario(ScenarioToolkit toolkit, ScenarioProperties properties) {
        super(toolkit);
        this.properties = properties;
    }

    @Override
    public String scenarioName() {
        return "consumer-outage";
    }

    @Override
    public void run(ScenarioRunContext ctx) {
        recordHttp(ctx.runId(), "PUT /demo/payment-behavior", paymentServiceClient.setBehavior("DEFAULT_SUCCESS"));
        recordHttp(ctx.runId(), "POST /demo/consumers/" + LISTENER_ID + "/pause",
                consumerControlClient.pauseInventoryConsumer(LISTENER_ID));

        OrderServiceClient.OrderCreationResult order =
                createOrder(ctx.runId(), "SKU-001", 1, CUSTOMER_NAME);
        ctx.setPrimaryOrderId().accept(order.orderId());

        sleep(properties.consumerOutagePauseMs());

        recordHttp(ctx.runId(), "POST /demo/consumers/" + LISTENER_ID + "/resume",
                consumerControlClient.resumeInventoryConsumer(LISTENER_ID));

        orderStatusWatcher.awaitTerminal(ctx.runId(), order.orderId());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
