package com.orderfulfillment.common.kafka;

import com.orderfulfillment.common.NotFoundException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * The real machinery behind every service's {@code /demo/consumers} endpoints: genuine Spring Kafka
 * listener-container pause and resume, driven through {@link KafkaListenerEndpointRegistry}.
 *
 * <p>docs/scenarios.md's Scenario 5 is explicit that this must not be faked — "a genuine Spring
 * Kafka listener-container pause, not a discarded message or a simulated delay". Nothing here
 * drops, buffers or delays a record: a paused container stops fetching, the records stay on the
 * topic, and on resume the consumer carries on from its committed offset.
 *
 * <h2>pause() rather than stop()</h2>
 *
 * <p>{@code stop()} would also halt processing, but it leaves the consumer group, triggering a
 * rebalance on the way out and another on the way back — so a multi-instance deployment would
 * reassign the paused instance's partitions to a running one and quietly process the "backlog"
 * anyway, which is the opposite of what the scenario demonstrates. {@code pause()} keeps the
 * consumer in the group and its partitions assigned; it simply stops delivering records. That also
 * matches the frozen contract's vocabulary — the OpenAPI field is {@code paused}.
 */
@Component
public class ConsumerControl {

    private static final Logger log = LoggerFactory.getLogger(ConsumerControl.class);

    /**
     * A pause takes effect on the container's next poll, so the call returns only once the pause is
     * real — otherwise Scenario 5 could publish its first order into the gap and see it processed by
     * a consumer it believes it has already paused. Bounded, and the observed state is reported
     * either way rather than an optimistic {@code true}.
     */
    private static final Duration EFFECTIVE_TIMEOUT = Duration.ofSeconds(10);
    private static final long POLL_PARK_NANOS = 20_000_000L; // 20 ms between state checks

    private final KafkaListenerEndpointRegistry registry;

    public ConsumerControl(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    /** Every {@code @KafkaListener} in this service and whether it is currently paused. */
    public List<ConsumerState> list() {
        return registry.getListenerContainers().stream()
                .map(ConsumerControl::toState)
                .sorted(Comparator.comparing(ConsumerState::name))
                .toList();
    }

    /** Idempotent: pausing an already-paused listener succeeds, per the frozen OpenAPI description. */
    public ConsumerState pause(String consumerName) {
        MessageListenerContainer container = require(consumerName);
        log.info("Pausing Kafka listener '{}' (demo control)", consumerName);
        container.pause();
        awaitPausedState(container, true);
        return toState(container);
    }

    /** Idempotent: resuming a running listener succeeds, per the frozen OpenAPI description. */
    public ConsumerState resume(String consumerName) {
        MessageListenerContainer container = require(consumerName);
        log.info("Resuming Kafka listener '{}' (demo control)", consumerName);
        container.resume();
        awaitPausedState(container, false);
        return toState(container);
    }

    private MessageListenerContainer require(String consumerName) {
        MessageListenerContainer container = registry.getListenerContainer(consumerName);
        if (container == null) {
            throw new NotFoundException("CONSUMER_NOT_FOUND",
                    "No Kafka listener named '" + consumerName + "' in this service");
        }
        return container;
    }

    private static void awaitPausedState(MessageListenerContainer container, boolean paused) {
        long deadline = System.nanoTime() + EFFECTIVE_TIMEOUT.toNanos();
        while (container.isContainerPaused() != paused && System.nanoTime() < deadline) {
            LockSupport.parkNanos(POLL_PARK_NANOS);
        }
        if (container.isContainerPaused() != paused) {
            log.warn("Kafka listener '{}' did not reach paused={} within {}; reporting its actual state",
                    container.getListenerId(), paused, EFFECTIVE_TIMEOUT);
        }
    }

    private static ConsumerState toState(MessageListenerContainer container) {
        String[] topics = container.getContainerProperties().getTopics();
        return new ConsumerState(
                container.getListenerId(),
                topics == null || topics.length == 0 ? null : String.join(",", topics),
                container.getGroupId(),
                container.isContainerPaused());
    }
}
