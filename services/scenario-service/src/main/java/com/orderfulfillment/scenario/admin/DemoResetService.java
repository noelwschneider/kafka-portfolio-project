package com.orderfulfillment.scenario.admin;

import com.orderfulfillment.common.ConflictException;
import com.orderfulfillment.scenario.clients.ConsumerControlClient;
import com.orderfulfillment.scenario.clients.InventoryServiceClient;
import com.orderfulfillment.scenario.clients.PaymentServiceClient;
import com.orderfulfillment.scenario.domain.ScenarioRunRepository;
import com.orderfulfillment.scenario.domain.ScenarioRunStatus;
import com.orderfulfillment.scenario.dto.ResetResultDto;
import com.orderfulfillment.scenario.runtime.RunRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code POST /demo/reset} (docs/openapi/scenario-service.yaml). Restores seed inventory, resumes any
 * consumer a scenario left paused, and clears Payment Service's behavior override — so any scenario
 * can be re-run from a known state.
 *
 * <p><b>History-retention decision (Phase 5 implementation call, left open by the OpenAPI doc):</b>
 * reset does <em>not</em> delete {@code scenario_runs}, {@code scenario_run_timeline}, or the new
 * {@code events} projection. docs/scenarios.md's own text argues for this ("the Event Explorer and run
 * history are the demo's evidence trail") — deleting history on every reset would mean a reviewer who
 * resets between two scenarios loses the ability to compare them, which is the opposite of what a demo
 * evidence trail is for. Nothing about correctness depends on clearing them: {@code scenario_runs.id}
 * is a monotonic sequence that never repeats, so old rows cannot collide with new ones, and stale
 * {@code RUNNING} rows cannot exist post-reset because reset itself refuses to run while one is
 * {@code RUNNING} (see the 409 guard below).
 */
@Service
public class DemoResetService {

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    /** docs/planning/sprint-1/backend-design.md's Seed Data section / docs/db-ownership.md's price table. */
    private static final Map<String, Integer> SEED_QUANTITIES = new LinkedHashMap<>();

    static {
        SEED_QUANTITIES.put("SKU-001", 10);
        SEED_QUANTITIES.put("SKU-002", 5);
        SEED_QUANTITIES.put("SKU-003", 100);
        SEED_QUANTITIES.put("SKU-004", 2);
    }

    private final ScenarioRunRepository runRepository;
    private final RunRegistry runRegistry;
    private final InventoryServiceClient inventoryServiceClient;
    private final ConsumerControlClient consumerControlClient;
    private final PaymentServiceClient paymentServiceClient;

    public DemoResetService(ScenarioRunRepository runRepository, RunRegistry runRegistry,
                             InventoryServiceClient inventoryServiceClient,
                             ConsumerControlClient consumerControlClient,
                             PaymentServiceClient paymentServiceClient) {
        this.runRepository = runRepository;
        this.runRegistry = runRegistry;
        this.inventoryServiceClient = inventoryServiceClient;
        this.consumerControlClient = consumerControlClient;
        this.paymentServiceClient = paymentServiceClient;
    }

    public ResetResultDto reset() {
        if (runRegistry.anyRunning() || runRepository.existsByStatus(ScenarioRunStatus.RUNNING)) {
            throw new ConflictException("RESET_CONFLICT", "A scenario run is still in progress");
        }

        boolean inventoryRestored = restoreInventory();
        List<String> pausedBeforeResume = consumerControlClient.pausedConsumers();
        resumePaused(pausedBeforeResume);
        boolean paymentBehaviorCleared = clearPaymentBehavior();

        return new ResetResultDto(inventoryRestored, pausedBeforeResume, paymentBehaviorCleared, Instant.now());
    }

    private boolean restoreInventory() {
        boolean allOk = true;
        for (Map.Entry<String, Integer> entry : SEED_QUANTITIES.entrySet()) {
            try {
                inventoryServiceClient.restoreInventory(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("Failed to restore seed quantity for {}: {}", entry.getKey(), e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }

    private void resumePaused(List<String> pausedConsumers) {
        for (String qualified : pausedConsumers) {
            String[] parts = qualified.split("/", 2);
            if (parts.length != 2) {
                continue;
            }
            String service = parts[0];
            String listener = parts[1];
            try {
                if ("inventory-service".equals(service)) {
                    consumerControlClient.resumeInventoryConsumer(listener);
                } else if ("fulfillment-service".equals(service)) {
                    consumerControlClient.resumeFulfillmentConsumer(listener);
                }
            } catch (Exception e) {
                log.warn("Failed to resume {}: {}", qualified, e.getMessage());
            }
        }
    }

    private boolean clearPaymentBehavior() {
        try {
            paymentServiceClient.clearBehavior();
            return true;
        } catch (Exception e) {
            log.warn("Failed to clear payment behavior override: {}", e.getMessage());
            return false;
        }
    }
}
