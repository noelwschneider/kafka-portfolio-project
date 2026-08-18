package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderfulfillment.common.kafka.DlqHeaders;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Scenario 6 — Poison Message / DLQ (docs/scenarios.md), Inventory Service's half.
 *
 * <p>Both poison records here are the kinds the scenario names — "an unparseable {@code payload}
 * body, or an {@code eventVersion} no consumer knows" — and both are put on {@code orders.events}
 * as real bytes, so the real {@link InventoryOrderEventsConsumer} and the real error handler decide
 * what happens. Nothing is stubbed.
 *
 * <p><b>Why these two show one delivery and not four.</b> docs/events/event-catalog.md §5 requires
 * an unknown {@code eventVersion} to be treated as non-retryable ("Retrying cannot fix a schema it
 * doesn't understand"), and the same is true of bytes that will not parse. So the honest behaviour
 * is to dead-letter them immediately, and the DLQ record says so: {@code x-delivery-attempts: 1},
 * {@code x-failure-retryable: false}. Asserting a retry count of 1 here is the assertion that the
 * classification is real — a service that retried these anyway would fail this test. Bounded
 * retries with backoff on the <em>retryable</em> arm are proven separately, against a real Kafka,
 * by {@link KafkaRetryAndDlqIntegrationTest}.
 *
 * <p>Every piece of metadata a future DLQ-inspector UI needs is asserted present here: original
 * topic, partition, offset, consumer group, exception class and message, and the retry count.
 */
class InventoryPoisonMessageIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anEnvelopeWithAnUnknownEventVersionIsDeadLetteredWithoutRetrying() {
        String orderId = "order-poison-" + UUID.randomUUID();
        String poison = """
                {"eventId":"%s","eventType":"%s","eventVersion":99,"occurredAt":"%s",
                 "correlationId":"%s","aggregateId":"%s",
                 "payload":{"orderId":"%s","customerId":"demo-customer",
                            "items":[{"sku":"SKU-003","quantity":1}]}}
                """.formatted(UUID.randomUUID(), EventTypes.ORDER_CREATED, Instant.now(),
                UUID.randomUUID(), orderId, orderId);

        ConsumerRecord<String, String> dlq = publishAndAwaitDlq(orderId, poison);

        assertThat(header(dlq, DlqHeaders.FAILURE_CLASS))
                .isEqualTo("com.orderfulfillment.common.kafka.UnsupportedEventVersionException");
        assertThat(header(dlq, DlqHeaders.FAILURE_MESSAGE)).contains("99");
        assertThat(header(dlq, DlqHeaders.DELIVERY_ATTEMPTS)).isEqualTo("1");
        assertThat(header(dlq, DlqHeaders.RETRYABLE)).isEqualTo("false");
        assertCommonMetadata(dlq, orderId);
    }

    @Test
    void anUnparseablePayloadIsDeadLetteredWithoutRetrying() {
        String orderId = "order-poison-" + UUID.randomUUID();
        // A structurally valid envelope at the version the codec accepts, whose payload is a bare
        // string where an OrderCreated object belongs. It gets past decode() and fails in
        // payloadAs() — a different code path from the version check above, and the one a genuinely
        // corrupt producer would exercise.
        String poison = """
                {"eventId":"%s","eventType":"%s","eventVersion":%d,"occurredAt":"%s",
                 "correlationId":"%s","aggregateId":"%s","payload":"this is not an OrderCreated"}
                """.formatted(UUID.randomUUID(), EventTypes.ORDER_CREATED, EventTypes.CURRENT_VERSION,
                Instant.now(), UUID.randomUUID(), orderId, orderId);

        ConsumerRecord<String, String> dlq = publishAndAwaitDlq(orderId, poison);

        assertThat(header(dlq, DlqHeaders.FAILURE_CLASS)).startsWith("tools.jackson");
        assertThat(header(dlq, DlqHeaders.DELIVERY_ATTEMPTS)).isEqualTo("1");
        assertThat(header(dlq, DlqHeaders.RETRYABLE)).isEqualTo("false");
        assertCommonMetadata(dlq, orderId);
    }

    /** The metadata an operator (and Scenario 6's promised UI) needs to act on a dead-lettered record. */
    private void assertCommonMetadata(ConsumerRecord<String, String> dlq, String orderId) {
        assertThat(dlq.key()).isEqualTo(orderId); // keyed by orderId, so per-order ordering survives into the DLQ
        assertThat(header(dlq, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(KafkaTopics.ORDERS_EVENTS);
        assertThat(intHeader(dlq, KafkaHeaders.DLT_ORIGINAL_PARTITION)).isBetween(0, 2);
        assertThat(longHeader(dlq, KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNegative();
        assertThat(header(dlq, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo("inventory-service");
        assertThat(header(dlq, KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNotBlank();
        assertThat(header(dlq, DlqHeaders.FAILURE_MESSAGE)).isNotBlank();
        assertThat(Instant.parse(header(dlq, DlqHeaders.DEAD_LETTERED_AT))).isNotNull();
    }

    private ConsumerRecord<String, String> publishAndAwaitDlq(String orderId, String poison) {
        Consumer<String, String> dlqConsumer = rawConsumer(KafkaTopics.INVENTORY_DLQ);
        try {
            kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, orderId, poison);

            List<ConsumerRecord<String, String>> matches = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (matches.isEmpty() && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(dlqConsumer, Duration.ofSeconds(2));
                records.forEach(r -> {
                    if (orderId.equals(r.key())) {
                        matches.add(r);
                    }
                });
            }
            assertThat(matches).as("poison record for %s should land on %s", orderId, KafkaTopics.INVENTORY_DLQ)
                    .hasSize(1);
            // The dead-lettered record carries the original bytes, so a corrected replay is possible.
            assertThat(matches.getFirst().value()).isEqualTo(poison);
            return matches.getFirst();
        } finally {
            dlqConsumer.close();
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static int intHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        return ByteBuffer.wrap(header.value()).getInt();
    }

    private static long longHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        return ByteBuffer.wrap(header.value()).getLong();
    }
}
