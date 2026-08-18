package com.orderfulfillment.common.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * Builds the one Kafka error handler every service in this project uses, parameterized only by the
 * DLQ topic it routes to. See docs/reliability-pattern.md §"Retry and DLQ" for the policy and the
 * reasoning behind the numbers; this class is that document made executable.
 *
 * <p>Shared rather than copy-pasted because the parts that matter — which exceptions are worth
 * retrying, how many times, and what metadata the dead-lettered record carries — must be the same
 * in all four services for the DLQ inspector and the reliability claims to mean one thing. The only
 * per-service input is the destination topic (docs/events/event-catalog.md §2 gives each consuming
 * service its own {@code <domain>.dlq}).
 */
@Component
public class ConsumerErrorHandlerFactory {

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorHandlerFactory.class);

    /**
     * Three retries after the initial delivery = four deliveries in all, spaced 0.5s / 1s / 2s
     * (~3.5s total). Chosen against three constraints rather than picked round:
     * <ul>
     *   <li>Retrying blocks the partition — every later record for those orders waits. A budget an
     *       order of magnitude larger would turn one poison record into a visible outage of the
     *       whole partition, which is a worse failure than dead-lettering promptly.</li>
     *   <li>The retryable class here is genuinely transient (lock contention, a connection blip);
     *       such failures clear in milliseconds-to-seconds, so extra attempts buy nothing.</li>
     *   <li>Scenario 6 asks a reviewer to <em>watch</em> retries happen and then see the record land
     *       in the DLQ. 3.5 seconds is long enough to see in a UI and short enough to sit through.</li>
     * </ul>
     * Exponential rather than fixed so that the last attempt is meaningfully later than the first —
     * the point of backoff is to sample a different moment, not to wait a fixed amount.
     */
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_INTERVAL_MS = 500L;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_INTERVAL_MS = 2_000L;

    /**
     * Failures that retrying provably cannot fix, so they are dead-lettered on the first delivery
     * instead of blocking their partition for 3.5 seconds first.
     *
     * <ul>
     *   <li>{@link UnsupportedEventVersionException} — required to be non-retryable by
     *       docs/events/event-catalog.md §5: "Retrying cannot fix a schema it doesn't understand."</li>
     *   <li>{@link JacksonException} — the record's bytes are not the envelope/payload we expect.
     *       The same bytes will not parse differently in 500ms.</li>
     *   <li>{@link NonTransientDataAccessException} — Spring's own name for "this will fail the same
     *       way if you try again": constraint violations, bad SQL, impossible domain data. Its
     *       sibling {@code TransientDataAccessException} (which
     *       {@code ObjectOptimisticLockingFailureException} extends) is deliberately <em>not</em>
     *       here — see the class-level note in docs/reliability-pattern.md on Gap 1.</li>
     *   <li>{@link IllegalArgumentException} — a malformed value that reached the domain layer.</li>
     * </ul>
     *
     * <p>Everything else defaults to retryable. That is the safer default for an <em>unrecognised</em>
     * failure: a wrongly-retried permanent failure costs 3.5 seconds and still ends in the DLQ with
     * its metadata intact, whereas a wrongly-non-retried transient failure discards real work.
     * Spring Kafka's own built-in non-retryable defaults (deserialization, message conversion,
     * {@code ClassCastException}, …) remain in force alongside these.
     */
    private static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(
            UnsupportedEventVersionException.class,
            JacksonException.class,
            NonTransientDataAccessException.class,
            IllegalArgumentException.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DeliveryAttemptTracker deliveryAttemptTracker;

    public ConsumerErrorHandlerFactory(KafkaTemplate<String, String> kafkaTemplate,
                                       DeliveryAttemptTracker deliveryAttemptTracker) {
        this.kafkaTemplate = kafkaTemplate;
        this.deliveryAttemptTracker = deliveryAttemptTracker;
    }

    /**
     * @param dlqTopic this service's own dead-letter topic — {@link KafkaTopics#INVENTORY_DLQ} and
     *                 friends. A service dead-letters everything it fails to process here,
     *                 regardless of which topic the record arrived on: the failure is the
     *                 consumer's, not the publisher's.
     */
    public DefaultErrorHandler create(String dlqTopic) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // Partition -1 leaves the partition unset on the outbound record, so the producer
                // partitions it by key (= orderId), keeping per-order ordering inside the DLQ too.
                // Resolving to the source record's own partition number would instead assume the
                // DLQ has at least as many partitions as every source topic.
                (record, exception) -> new TopicPartition(dlqTopic, -1));
        recoverer.setHeadersFunction((record, exception) -> failureHeaders(record, exception, dlqTopic));

        // maxAttempts counts the backoff intervals handed to the error handler, i.e. the retries
        // after the initial delivery; the execution then reports STOP and the recoverer runs.
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_INTERVAL_MS, MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);
        backOff.setMaxAttempts(MAX_RETRIES);
        backOff.setJitter(0L); // deterministic spacing: the retry timing is part of what Scenario 6 shows

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        NON_RETRYABLE.forEach(errorHandler::addNotRetryableExceptions);
        errorHandler.setRetryListeners(deliveryAttemptTracker);
        return errorHandler;
    }

    private Headers failureHeaders(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                                   Exception exception, String dlqTopic) {
        int attempts = deliveryAttemptTracker.attemptsForAndClear(record);
        boolean retryable = isRetryable(exception);
        Throwable cause = rootCause(exception);
        // The full stack goes to the log as well as into the DLQ header: an operator looking at the
        // service's own logs should not have to go and read the dead-letter topic to find out why.
        log.error("Dead-lettering {}-{}@{} to {} after {} delivery attempt(s) ({} failure)",
                record.topic(), record.partition(), record.offset(), dlqTopic, attempts,
                retryable ? "retryable" : "non-retryable", exception);
        Headers headers = new RecordHeaders();
        headers.add(DlqHeaders.DELIVERY_ATTEMPTS, String.valueOf(attempts).getBytes(StandardCharsets.UTF_8));
        headers.add(DlqHeaders.RETRYABLE, String.valueOf(retryable).getBytes(StandardCharsets.UTF_8));
        headers.add(DlqHeaders.DEAD_LETTERED_AT, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
        headers.add(DlqHeaders.FAILURE_CLASS, cause.getClass().getName().getBytes(StandardCharsets.UTF_8));
        headers.add(DlqHeaders.FAILURE_MESSAGE,
                String.valueOf(cause.getMessage()).getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * The same classification {@link #NON_RETRYABLE} gives the error handler, exposed so the DLQ
     * record can state which arm it took rather than leaving a reader to infer it from a count.
     * Causes are walked because a listener exception arrives wrapped in
     * {@code ListenerExecutionFailedException}.
     */
    public static boolean isRetryable(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause() == t ? null : t.getCause()) {
            for (Class<? extends Exception> nonRetryable : NON_RETRYABLE) {
                if (nonRetryable.isInstance(t)) {
                    return false;
                }
            }
        }
        return true;
    }
}
