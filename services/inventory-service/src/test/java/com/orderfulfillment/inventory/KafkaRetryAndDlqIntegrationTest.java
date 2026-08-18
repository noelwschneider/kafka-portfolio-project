package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderfulfillment.common.kafka.DlqHeaders;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The retryable arm of the shared retry/DLQ policy, proven end to end against a real Kafka: a
 * failure classified retryable is redelivered a bounded number of times, with backoff, and then
 * dead-lettered with the true attempt count.
 *
 * <p>This is deliberately separate from {@link InventoryPoisonMessageIntegrationTest}. Scenario 6's
 * poison records are non-retryable by contract, so they can never demonstrate retries; and no
 * <em>record</em> can demonstrate them either, because in this codebase every failure a record's own
 * content can cause is deterministic, and a deterministic failure is by definition not worth
 * retrying. The retryable class here is infrastructural: lock contention and transient database
 * faults. So the stimulus is a listener that raises one, on a topic of its own — while the machinery
 * under test (the {@code DefaultErrorHandler} bean built by
 * {@code ConsumerErrorHandlerFactory}, its backoff, its classifier, its
 * {@code DeadLetterPublishingRecoverer} and the attempt tracker) is the unmodified production
 * configuration this service runs with.
 *
 * <p>The exception it raises is not arbitrary: {@link ObjectOptimisticLockingFailureException} is
 * exactly what escapes {@link InventoryService#reserve} when its 25-attempt optimistic-lock budget
 * exhausts. That path had no defined outcome before Phase 4 — the record was logged and skipped, and
 * the order was stranded in PENDING (docs/agent-reports/phase-3-inventory-concurrency.md §7.1). This
 * test is the proof that it now ends somewhere inspectable instead.
 */
@Import(KafkaRetryAndDlqIntegrationTest.RetryProbeListener.class)
class KafkaRetryAndDlqIntegrationTest extends AbstractIntegrationTest {

    static final String PROBE_TOPIC = "test.retry-probe";

    @Test
    void aRetryableFailureIsRetriedWithBackoffAndThenDeadLettered() {
        String key = "order-retry-" + UUID.randomUUID();
        Consumer<String, String> dlqConsumer = rawConsumer(KafkaTopics.INVENTORY_DLQ);
        try {
            RetryProbeListener.DELIVERY_TIMES_NANOS.clear();
            long publishedAt = System.nanoTime();
            kafkaTemplate.send(PROBE_TOPIC, key, "{\"probe\":true}");

            ConsumerRecord<String, String> dlq = awaitDlqRecord(dlqConsumer, key);

            // Bounded: the initial delivery plus three retries, and no more.
            assertThat(RetryProbeListener.DELIVERY_TIMES_NANOS).hasSize(4);
            assertThat(header(dlq, DlqHeaders.DELIVERY_ATTEMPTS)).isEqualTo("4");
            assertThat(header(dlq, DlqHeaders.RETRYABLE)).isEqualTo("true");
            assertThat(header(dlq, DlqHeaders.FAILURE_CLASS))
                    .isEqualTo("org.springframework.orm.ObjectOptimisticLockingFailureException");
            assertThat(header(dlq, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(PROBE_TOPIC);

            // Backoff, not a hot loop: 0.5s + 1s + 2s of configured waiting means the last delivery
            // cannot have happened within 3s of the first. Asserted as a lower bound only — an upper
            // bound would be asserting on the machine's scheduler.
            List<Long> deliveries = new ArrayList<>(RetryProbeListener.DELIVERY_TIMES_NANOS);
            long spanMillis = Duration.ofNanos(deliveries.getLast() - deliveries.getFirst()).toMillis();
            assertThat(spanMillis).isGreaterThanOrEqualTo(3_000L);
            assertThat(Duration.ofNanos(deliveries.getFirst() - publishedAt).toMillis())
                    .as("the first delivery is immediate; only retries back off")
                    .isLessThan(3_000L);
        } finally {
            dlqConsumer.close();
        }
    }

    private ConsumerRecord<String, String> awaitDlqRecord(Consumer<String, String> dlqConsumer, String key) {
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (matches.isEmpty() && System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(dlqConsumer, Duration.ofSeconds(2));
            records.forEach(r -> {
                if (key.equals(r.key())) {
                    matches.add(r);
                }
            });
        }
        assertThat(matches).as("retry-exhausted record should land on %s", KafkaTopics.INVENTORY_DLQ).hasSize(1);
        return matches.getFirst();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * The stimulus: a listener on its own topic that always fails with the exception Inventory
     * Service's retry-exhausted reservation path throws. It shares the production listener container
     * factory, and therefore the production error handler, backoff and DLQ routing.
     */
    @TestConfiguration
    static class RetryProbeListener {

        static final ConcurrentLinkedQueue<Long> DELIVERY_TIMES_NANOS = new ConcurrentLinkedQueue<>();

        /**
         * Created up front rather than left to broker auto-creation: a consumer that subscribes to a
         * topic which does not exist yet only notices it on its next metadata refresh, which
         * defaults to five minutes.
         */
        @Bean
        NewTopic retryProbeTopic() {
            return TopicBuilder.name(PROBE_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        RetryProbe retryProbe() {
            return new RetryProbe();
        }

        static class RetryProbe {
            @KafkaListener(id = "retry-probe", topics = PROBE_TOPIC, groupId = "inventory-service-retry-probe",
                    concurrency = "1")
            public void onMessage(String message) {
                DELIVERY_TIMES_NANOS.add(System.nanoTime());
                throw new ObjectOptimisticLockingFailureException(InventoryItemEntity.class, "SKU-PROBE");
            }
        }
    }
}
