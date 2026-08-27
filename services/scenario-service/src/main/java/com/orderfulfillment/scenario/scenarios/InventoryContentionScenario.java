package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.scenario.clients.OrderServiceClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * docs/scenarios.md Scenario 7 — Inventory Contention. Two genuinely concurrent
 * {@code POST /api/orders} calls for SKU-004 (seeded stock: 2), each requesting 2 units — Inventory
 * Service's optimistic-locking reservation logic (docs/db-ownership.md's {@code version} column) is
 * what decides the winner and loser; this scenario only has to make the two requests actually overlap.
 */
@Component
public class InventoryContentionScenario extends AbstractScenarioRunner {

    public InventoryContentionScenario(ScenarioToolkit toolkit) {
        super(toolkit);
    }

    @Override
    public String scenarioName() {
        return "inventory-contention";
    }

    @Override
    public void run(ScenarioRunContext ctx) {
        recordHttp(ctx.runId(), "PUT /demo/payment-behavior", paymentServiceClient.setBehavior("DEFAULT_SUCCESS"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<OrderServiceClient.OrderCreationResult> first =
                    CompletableFuture.supplyAsync(() -> createOnThisCorrelation(ctx), pool);
            CompletableFuture<OrderServiceClient.OrderCreationResult> second =
                    CompletableFuture.supplyAsync(() -> createOnThisCorrelation(ctx), pool);
            CompletableFuture.allOf(first, second).join();

            OrderServiceClient.OrderCreationResult orderA = first.join();
            OrderServiceClient.OrderCreationResult orderB = second.join();

            CompletableFuture<Void> watchA = CompletableFuture.runAsync(
                    () -> watchOnThisCorrelation(ctx, orderA.orderId()), pool);
            CompletableFuture<Void> watchB = CompletableFuture.runAsync(
                    () -> watchOnThisCorrelation(ctx, orderB.orderId()), pool);
            CompletableFuture.allOf(watchA, watchB).join();
        } finally {
            pool.shutdown();
        }
        // orderId is deliberately left null on the run: this scenario creates two orders, and each is
        // named individually in its own timeline entries (ScenarioRun.orderId's documented meaning).
    }

    private OrderServiceClient.OrderCreationResult createOnThisCorrelation(ScenarioRunContext ctx) {
        return runOnThisCorrelation(ctx, () -> createOrder(ctx.runId(), "SKU-004", 2, "Cara Contention"));
    }

    private void watchOnThisCorrelation(ScenarioRunContext ctx, String orderId) {
        runOnThisCorrelation(ctx, () -> {
            orderStatusWatcher.awaitTerminal(ctx.runId(), orderId);
            return null;
        });
    }

    /** Each pool thread starts with no correlationId of its own; propagate the run's explicitly, the
     * same way CorrelationIdFilter does per HTTP request (see CorrelationIdHolder's Javadoc). */
    private <T> T runOnThisCorrelation(ScenarioRunContext ctx, java.util.function.Supplier<T> action) {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        CorrelationIdHolder.runInScope(ctx.correlationId(), () -> result.set(action.get()));
        return result.get();
    }
}
