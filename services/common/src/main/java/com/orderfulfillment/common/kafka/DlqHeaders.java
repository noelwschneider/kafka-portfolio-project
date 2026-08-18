package com.orderfulfillment.common.kafka;

/**
 * The failure-metadata headers this project adds to a dead-lettered record, on top of the standard
 * {@code kafka_dlt-*} headers Spring Kafka's {@code DeadLetterPublishingRecoverer} already writes
 * (original topic, partition, offset, timestamp, consumer group, exception class/message/stacktrace).
 *
 * <p>docs/scenarios.md's Scenario 6 requires the DLQ record to be inspectable with "the error
 * inspectable and the retry count shown", so the retry count and a dead-lettering timestamp — the
 * two things the standard headers do not provide — are added explicitly. Names are lower-case and
 * {@code x-} prefixed so they cannot collide with Spring's, and are listed here rather than inline
 * so the future DLQ inspector UI has one place to read them from.
 */
public final class DlqHeaders {

    /** Number of times the record was delivered to the listener before being dead-lettered. */
    public static final String DELIVERY_ATTEMPTS = "x-delivery-attempts";

    /**
     * Class name of the <em>root cause</em>, as opposed to Spring's {@code kafka_dlt-exception-fqcn},
     * which reports the {@code ListenerExecutionFailedException} wrapper the framework threw. The
     * wrapper is the same for every failure and so says nothing; the root cause is the answer to
     * "why did this record fail", and it is what the classifier actually acted on.
     */
    public static final String FAILURE_CLASS = "x-failure-class";

    /** Message of the root cause, for the same reason as {@link #FAILURE_CLASS}. */
    public static final String FAILURE_MESSAGE = "x-failure-message";

    /** Whether the failure was classified retryable ({@code "true"}) or not ({@code "false"}). */
    public static final String RETRYABLE = "x-failure-retryable";

    /** When the record was dead-lettered (RFC 3339), as opposed to when it was originally produced. */
    public static final String DEAD_LETTERED_AT = "x-dead-lettered-at";

    private DlqHeaders() {
    }
}
