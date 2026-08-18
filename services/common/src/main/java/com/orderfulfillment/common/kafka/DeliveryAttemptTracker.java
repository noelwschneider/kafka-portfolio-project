package com.orderfulfillment.common.kafka;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.stereotype.Component;

/**
 * Counts how many times each record was actually delivered to the listener before it was
 * dead-lettered, so the DLQ record can carry a real retry count rather than the configured maximum.
 *
 * <p>This exists because docs/scenarios.md's Scenario 6 promises a reviewer "the retry count shown",
 * and Spring Kafka's {@code DeadLetterPublishingRecoverer} does not stamp one: its standard headers
 * cover the original topic/partition/offset and the exception, but not how many attempts were made.
 * A configured maximum would be a guess — non-retryable failures are dead-lettered on the first
 * delivery, so "4" would be a lie for exactly the records Scenario 6 publishes.
 *
 * <p>Entries are keyed by the record's coordinates and removed when it is dead-lettered (and again
 * on {@link #recovered}/{@link #recoveryFailed}, which is harmless if the header function already
 * took it), so the map holds only records currently mid-retry — bounded by the number of listener
 * threads.
 */
@Component
public class DeliveryAttemptTracker implements RetryListener {

    private final Map<String, Integer> attemptsInFlight = new ConcurrentHashMap<>();

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
        attemptsInFlight.put(key(record), deliveryAttempt);
    }

    @Override
    public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
        attemptsInFlight.remove(key(record));
    }

    @Override
    public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
        attemptsInFlight.remove(key(record));
    }

    /**
     * How many times this record was delivered to the listener, consuming the count.
     *
     * <p>Defaults to {@code 1} when nothing was recorded: a failure classified as non-retryable
     * goes straight to the recoverer, which is one real delivery, not zero.
     */
    public int attemptsForAndClear(ConsumerRecord<?, ?> record) {
        Integer attempts = attemptsInFlight.remove(key(record));
        return attempts == null ? 1 : attempts;
    }

    private static String key(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }
}
