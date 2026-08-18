package com.orderfulfillment.fulfillment;

import com.orderfulfillment.common.kafka.ConsumerControl;
import com.orderfulfillment.common.kafka.ConsumerState;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/openapi/fulfillment-service.yaml's {@code /demo} namespace — the fault-injection surface,
 * deliberately kept out of {@code /api} (agent-guidance.md rule 9).
 *
 * <p>Frozen since Phase 0 and left unimplemented through Phases 1–3 because there were no listener
 * containers to control (docs/agent-reports/phase-1.md §3.8 declined to fabricate a paused flag with
 * no consumer behind it). Phase 4 implements them for real: every call reaches a live Spring Kafka
 * container through {@link ConsumerControl}, and the {@code paused} field in the response is read
 * back from the container rather than from anything this service remembers.
 *
 * <p>Scenario 5 (docs/scenarios.md) drives these endpoints.
 */
@RestController
@RequestMapping("/demo/consumers")
public class DemoConsumerController {

    private final ConsumerControl consumerControl;

    public DemoConsumerController(ConsumerControl consumerControl) {
        this.consumerControl = consumerControl;
    }

    @GetMapping
    public List<ConsumerState> listConsumers() {
        return consumerControl.list();
    }

    @PostMapping("/{consumerName}/pause")
    public ConsumerState pauseConsumer(@PathVariable String consumerName) {
        return consumerControl.pause(consumerName);
    }

    @PostMapping("/{consumerName}/resume")
    public ConsumerState resumeConsumer(@PathVariable String consumerName) {
        return consumerControl.resume(consumerName);
    }
}
